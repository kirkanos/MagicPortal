package net.kirkanos.mtgportal.scanner

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.kirkanos.mtgportal.scanner.catalog.CatalogSet
import net.kirkanos.mtgportal.scanner.catalog.CatalogStore
import net.kirkanos.mtgportal.scanner.catalog.Printing
import net.kirkanos.mtgportal.scanner.match.CardMatcher
import net.kirkanos.mtgportal.scanner.ocr.CardReadingParser
import net.kirkanos.mtgportal.scanner.ocr.OcrEngine
import net.kirkanos.mtgportal.scanner.ocr.ReadingFusion
import net.kirkanos.mtgportal.scanner.camera.CardCropper
import net.kirkanos.mtgportal.scanner.camera.GuideFrame
import net.kirkanos.mtgportal.scanner.camera.Pt
import net.kirkanos.mtgportal.scanner.camera.Quad
import net.kirkanos.mtgportal.scanner.camera.cutCard
import net.kirkanos.mtgportal.scanner.hash.CardHash
import net.kirkanos.mtgportal.scanner.hash.DHash
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(val printings: Int, val current: Boolean) : CatalogState
    data class Failed(val message: String) : CatalogState
}

data class ScannerUiState(
    val catalog: CatalogState = CatalogState.Loading,
    val items: List<SessionItem> = emptyList(),
    /** What the camera sees right now, before voting: feedback while aiming. */
    val seeing: String? = null,
    /** The card outline found in the last frame, as fractions of the frame; null = none. */
    val outline: Quad? = null,
    val torch: Boolean = false,
    /**
     * Applied to new cards: the camera cannot tell foil, and a card known
     * only by its picture has no readable language. Null language = as read
     * (English when nothing was read).
     */
    val defaultFoil: String = "normal",
    val defaultLanguage: String? = null,
    /** Lowercase set codes cards may come from; empty = all. */
    val sets: Set<String> = emptySet(),
    /** [CameraChoice.key] of the camera in use, null = the phone's default. */
    val camera: String? = null,
    /** The line the last scan added or counted up; shown with quick edits until the next one. */
    val last: Long? = null,
    /**
     * The line whose printing is being picked from all printings of the
     * card; scanning pauses meanwhile. Opens by itself when a card was only
     * recognised by its name.
     */
    val picking: Long? = null,
    /** Prices of the printings in the list, by Scryfall ID, as far as loaded. */
    val prices: Map<String, CardPrice> = emptyMap(),
    /** Printings whose price could not be loaded (offline, or unknown to the server). */
    val noPrice: Set<String> = emptySet(),
    /** The list of scanned cards is open; scanning pauses meanwhile. */
    val browsing: Boolean = false,
    /** Play a sound for each scanned card. */
    val sound: Boolean = true,
) {
    /** A line's price of one copy; null while loading or when none is known. */
    fun priceOf(item: SessionItem): Double? = prices[item.printing.scryfallId]?.forFinish(item.foil)

    /** Whether a line's price is still on its way. */
    fun priceLoading(item: SessionItem): Boolean = item.printing.scryfallId.let { it !in prices && it !in noPrice }

    /** Whether any line has a known price; without one a total of 0 € would mislead. */
    val hasPrices: Boolean
        get() = items.any { prices[it.printing.scryfallId]?.forFinish(it.foil) != null }

    /** What the list is worth, counting only lines with a known price. */
    val total: Double
        get() = items.sumOf { item -> prices[item.printing.scryfallId]?.forFinish(item.foil)?.let { it * item.quantity } ?: 0.0 }
}

/**
 * [dumpDir]: debug builds save camera frames there (at most one a second)
 * to tune the scanner offline. [price] asks the server for a printing's
 * prices; the on-device catalog has none, they change daily.
 */
class ScannerViewModel(
    private val store: CatalogStore,
    private val dumpDir: File? = null,
    private val prefs: ScannerPrefs? = null,
    private val price: suspend (setCode: String, collectorNumber: String) -> CardPrice? = { _, _ -> null },
) : ViewModel() {
    private val _state = MutableStateFlow(ScannerUiState(sets = prefs?.sets.orEmpty(), camera = prefs?.camera, sound = prefs?.sound ?: true))
    val state: StateFlow<ScannerUiState> = _state

    /** Fires once per card taken into the list, at once: vibrate. */
    private val _accepted = MutableSharedFlow<SessionItem>(extraBufferCapacity = 4)
    val accepted: SharedFlow<SessionItem> = _accepted

    /**
     * Fires once per card taken into the list when its price is known (or
     * did not come in time), with the price of one copy: the sound.
     */
    private val _priced = MutableSharedFlow<Double?>(extraBufferCapacity = 4)
    val priced: SharedFlow<Double?> = _priced

    /** Set while a frame is being read; the analyzer drops frames meanwhile. */
    val busy = AtomicBoolean(true)

    private val ocr = OcrEngine()
    private val session = ScanSession()
    private val fusion = ReadingFusion()
    private var matcher: CardMatcher? = null
    private var catalogSets: List<CatalogSet> = emptyList()
    private var setsByCode: Map<String, CatalogSet> = emptyMap()

    /** Printings whose price is loaded or on its way. */
    private val pricing = mutableSetOf<String>()

    init {
        loadCatalog()
        viewModelScope.launch {
            _state.map { s -> s.items.map { it.printing } }.distinctUntilChanged().collect { loadPrices(it) }
        }
    }

    /**
     * Loads the prices of new printings in the list, one request each. Offline
     * the list simply shows none; a failed printing is retried when the list
     * changes next.
     */
    private fun loadPrices(printings: List<Printing>) {
        printings.filter { pricing.add(it.scryfallId) }.forEach { p ->
            viewModelScope.launch {
                val r = try {
                    withContext(Dispatchers.IO) { price(p.setCode, p.collectorNumber) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (r == null) {
                    pricing.remove(p.scryfallId)
                    _state.update { it.copy(noPrice = it.noPrice + p.scryfallId) }
                } else {
                    _state.update { it.copy(prices = it.prices + (p.scryfallId to r), noPrice = it.noPrice - p.scryfallId) }
                }
            }
        }
    }

    fun loadCatalog() {
        _state.update { it.copy(catalog = CatalogState.Loading) }
        viewModelScope.launch {
            when (val r = store.load()) {
                is CatalogStore.Result.Ready -> {
                    matcher = CardMatcher(r.index, r.hashes).apply { restrictTo(_state.value.sets) }
                    catalogSets = r.index.sets.values.sortedByDescending { it.releasedAt }
                    setsByCode = r.index.sets
                    _state.update { it.copy(catalog = CatalogState.Ready(r.index.size, r.current)) }
                    busy.set(false)
                }
                is CatalogStore.Result.Failed ->
                    _state.update { it.copy(catalog = CatalogState.Failed(r.error.message ?: r.error.javaClass.simpleName)) }
            }
        }
    }

    /** Called from the analyzer thread with [busy] already set. */
    fun onFrame(frame: Bitmap) {
        // While a printing is picked the card is still under the camera; a
        // frame now could only count it again or correct the user's choice.
        if (_state.value.picking != null || _state.value.browsing) {
            frame.recycle()
            busy.set(false)
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val card = cutCard(frame)
            try {
                val raw = CardReadingParser.parse(ocr.read(card))
                val reading = fusion.fuse(raw)
                val (hash, outline) = artwork(frame)
                // Without a card outline the hash describes the table or a
                // misplaced card; it would only produce false matches.
                val match = matcher?.match(reading, hash.takeIf { outline != null })
                Log.d(TAG, "$reading -> ${match?.printing?.let { "${it.setCode}/${it.collectorNumber} ${it.name}" }} ${match?.source} ${match?.confidence}")
                dump(frame, raw.name ?: match?.printing?.name ?: "none")
                val defaults = _state.value
                val added = session.offer(match, reading.language ?: defaults.defaultLanguage, defaults.defaultFoil)
                _state.update { st ->
                    st.copy(
                        items = session.items,
                        seeing = match?.printing?.name,
                        outline = outline,
                        last = added?.id ?: st.last,
                        picking = if (added?.uncertain == true) added.id else st.picking,
                    )
                }
                if (added != null) {
                    _accepted.tryEmit(added)
                    announcePrice(added)
                }
            } catch (e: Exception) {
                Log.w(TAG, "frame failed", e)
            } finally {
                card.recycle()
                frame.recycle()
                busy.set(false)
            }
        }
    }

    /**
     * The card's artwork hash, from the card straightened by [CardCropper]
     * (the guide frame when no outline is found), at about the size of
     * Scryfall's small scans. Also returns the outline for the overlay.
     */
    private fun artwork(frame: Bitmap): Pair<CardHash, Quad?> {
        val sw = 480
        val sh = frame.height * sw / frame.width
        val small = Bitmap.createScaledBitmap(frame, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== frame) small.recycle()
        val gray = DHash.gray(px)
        val guide = GuideFrame.rect(sw.toFloat(), sh.toFloat())
        val found = CardCropper.find(gray, sw, sh, guide)
        val quad = found ?: Quad(Pt(guide.left, guide.top), Pt(guide.right, guide.top), Pt(guide.right, guide.bottom), Pt(guide.left, guide.bottom))
        val card = CardCropper.warp(gray, sw, sh, quad, 292, 408)
        return DHash.of(card, 292, 408) to found?.let { Quad(it.tl.frac(sw, sh), it.tr.frac(sw, sh), it.br.frac(sw, sh), it.bl.frac(sw, sh)) }
    }

    private fun Pt.frac(w: Int, h: Int) = Pt(x / w, y / h)

    private var lastDump = 0L
    private var dumped = 0

    private fun dump(frame: Bitmap, title: String) {
        val dir = dumpDir ?: return
        val now = System.currentTimeMillis()
        if (now - lastDump < 1000 || dumped >= 300) return
        lastDump = now
        dumped++
        runCatching {
            dir.mkdirs()
            val safe = title.replace(Regex("[^A-Za-z0-9]+"), "_").take(40)
            File(dir, "${now}_$safe.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
    }

    /**
     * Waits for the price of a card just taken (the list change starts its
     * request), then emits it for the sound. A slow server costs at most a
     * moment of silence, not the sound.
     */
    private fun announcePrice(item: SessionItem) {
        if (!_state.value.sound) return
        viewModelScope.launch {
            val id = item.printing.scryfallId
            val st = withTimeoutOrNull(PRICE_WAIT_MS) { _state.first { id in it.prices || id in it.noPrice } }
            _priced.tryEmit(st?.prices?.get(id)?.forFinish(item.foil))
        }
    }

    fun toggleSound() {
        val on = !_state.value.sound
        prefs?.sound = on
        _state.update { it.copy(sound = on) }
    }

    /** Opens or closes the list of scanned cards; scanning pauses while it is open. */
    fun setBrowsing(open: Boolean) = _state.update { it.copy(browsing = open) }

    fun toggleTorch() = _state.update { it.copy(torch = !it.torch) }

    /** Manual search in the on-device catalog; empty until it is loaded. */
    fun search(query: String): List<List<Printing>> = matcher?.search(query).orEmpty()

    /** All sets of the catalog, newest first; empty until it is loaded. */
    fun allSets(): List<CatalogSet> = catalogSets

    fun set(code: String): CatalogSet? = setsByCode[code.lowercase()]

    fun setSets(sets: Set<String>) {
        val codes = sets.map { it.lowercase() }.toSet()
        prefs?.sets = codes
        _state.update { it.copy(sets = codes) }
        viewModelScope.launch(Dispatchers.Default) { matcher?.restrictTo(codes) }
    }

    fun setCamera(key: String?) {
        prefs?.camera = key
        _state.update { it.copy(camera = key) }
    }

    fun setDefaultFoil(foil: String) = _state.update { it.copy(defaultFoil = foil) }
    fun setDefaultLanguage(language: String?) = _state.update { it.copy(defaultLanguage = language) }

    fun addManual(printings: List<Printing>) {
        val first = printings.firstOrNull() ?: return
        val s = _state.value
        val added = session.addManual(first, printings.drop(1).take(30), s.defaultLanguage ?: "en", s.defaultFoil)
        _state.update { it.copy(items = session.items, last = added.id) }
    }

    fun setQuantity(id: Long, quantity: Int) = edit(id) { it.copy(quantity = quantity.coerceIn(1, 999)) }
    fun setFoil(id: Long, foil: String) = edit(id) { it.copy(foil = foil) }
    fun setLanguage(id: Long, language: String) = edit(id) { it.copy(language = language) }

    /** The user picked another printing of the same card; theirs is certain. */
    fun setPrinting(id: Long, p: Printing) = edit(id) { item ->
        item.copy(printing = p, foil = p.finishFor(item.foil), alternatives = (listOf(item.printing) + item.alternatives).filter { it != p }, source = MatchSource.MANUAL, confidence = 1f)
    }

    fun remove(id: Long) {
        session.remove(id)
        _state.update { it.copy(items = session.items, last = it.last.takeIf { l -> l != id }, picking = it.picking.takeIf { p -> p != id }) }
    }

    /** Opens the printing picker for a line, or closes it (null) and scans on. */
    fun pickPrinting(id: Long?) = _state.update { it.copy(picking = id) }

    /** All printings of a line's card, its own and the matcher's alternatives as a fallback. */
    fun printingsOf(item: SessionItem): List<Printing> =
        matcher?.printingsOf(item.printing.name)?.takeIf { it.isNotEmpty() }
            ?: (listOf(item.printing) + item.alternatives).distinctBy { it.i }

    /** Loads a printing's price if it is not there yet, for the printing picker. */
    fun ensurePrice(p: Printing) = loadPrices(listOf(p))

    fun result() = session.result()

    private fun edit(id: Long, change: (SessionItem) -> SessionItem) {
        session.update(id, change)
        _state.update { it.copy(items = session.items) }
    }

    override fun onCleared() {
        ocr.close()
    }

    private companion object {
        const val TAG = "Scanner"
        const val PRICE_WAIT_MS = 1500L
    }
}
