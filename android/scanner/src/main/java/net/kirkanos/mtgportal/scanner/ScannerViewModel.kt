package net.kirkanos.mtgportal.scanner

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.scanner.catalog.CatalogStore
import net.kirkanos.mtgportal.scanner.catalog.Printing
import net.kirkanos.mtgportal.scanner.match.CardMatcher
import net.kirkanos.mtgportal.scanner.ocr.CardReadingParser
import net.kirkanos.mtgportal.scanner.ocr.OcrEngine
import net.kirkanos.mtgportal.scanner.ocr.ReadingFusion
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
    val torch: Boolean = false,
)

/**
 * [dumpDir]: debug builds save camera frames there (at most one a second,
 * only frames where a title was read) to tune the scanner offline.
 */
class ScannerViewModel(private val store: CatalogStore, private val dumpDir: File? = null) : ViewModel() {
    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state

    /** Fires once per card taken into the list: beep and vibrate. */
    private val _accepted = MutableSharedFlow<SessionItem>(extraBufferCapacity = 4)
    val accepted: SharedFlow<SessionItem> = _accepted

    /** Set while a frame is being read; the analyzer drops frames meanwhile. */
    val busy = AtomicBoolean(true)

    private val ocr = OcrEngine()
    private val session = ScanSession()
    private val fusion = ReadingFusion()
    private var matcher: CardMatcher? = null

    init {
        loadCatalog()
    }

    fun loadCatalog() {
        _state.update { it.copy(catalog = CatalogState.Loading) }
        viewModelScope.launch {
            when (val r = store.load()) {
                is CatalogStore.Result.Ready -> {
                    matcher = CardMatcher(r.index, r.hashes)
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
        viewModelScope.launch(Dispatchers.Default) {
            val card = cutCard(frame)
            try {
                val raw = CardReadingParser.parse(ocr.read(card))
                val reading = fusion.fuse(raw)
                val match = matcher?.match(reading, artworkHash(card))
                Log.d(TAG, "$reading -> ${match?.printing?.let { "${it.setCode}/${it.collectorNumber} ${it.name}" }} ${match?.source} ${match?.confidence}")
                if (raw.name != null) dump(frame, raw.name)
                val added = session.offer(match, reading.language)
                _state.update { it.copy(items = session.items, seeing = match?.printing?.name) }
                if (added != null) _accepted.tryEmit(added)
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
     * The card's artwork hash, computed on a copy at about the size of
     * Scryfall's small scans: the box average over 1,200 camera pixels and
     * over 146 scan pixels then see the same detail.
     */
    private fun artworkHash(card: Bitmap): CardHash {
        val small = Bitmap.createScaledBitmap(card, 292, 408, true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== card) small.recycle()
        return DHash.of(DHash.gray(px), 292, 408)
    }

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

    fun toggleTorch() = _state.update { it.copy(torch = !it.torch) }

    fun setQuantity(id: Long, quantity: Int) = edit(id) { it.copy(quantity = quantity.coerceIn(1, 999)) }
    fun setFoil(id: Long, foil: String) = edit(id) { it.copy(foil = foil) }
    fun setLanguage(id: Long, language: String) = edit(id) { it.copy(language = language) }

    /** The user picked another printing of the same card; theirs is certain. */
    fun setPrinting(id: Long, p: Printing) = edit(id) { item ->
        item.copy(printing = p, alternatives = (listOf(item.printing) + item.alternatives).filter { it != p }, source = MatchSource.MANUAL, confidence = 1f)
    }

    fun remove(id: Long) {
        session.remove(id)
        _state.update { it.copy(items = session.items) }
    }

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
    }
}
