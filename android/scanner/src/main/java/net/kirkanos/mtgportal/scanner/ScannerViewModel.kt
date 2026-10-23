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

class ScannerViewModel(private val store: CatalogStore) : ViewModel() {
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
                    matcher = CardMatcher(r.index)
                    _state.update { it.copy(catalog = CatalogState.Ready(r.index.size, r.current)) }
                    busy.set(false)
                }
                is CatalogStore.Result.Failed ->
                    _state.update { it.copy(catalog = CatalogState.Failed(r.error.message ?: r.error.javaClass.simpleName)) }
            }
        }
    }

    /** Called from the analyzer thread with [busy] already set. */
    fun onCard(card: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val reading = fusion.fuse(CardReadingParser.parse(ocr.read(card)))
                val match = matcher?.match(reading)
                Log.d(TAG, "$reading -> ${match?.printing?.let { "${it.setCode}/${it.collectorNumber} ${it.name}" }} ${match?.source} ${match?.confidence}")
                val added = session.offer(match, reading.language)
                _state.update { it.copy(items = session.items, seeing = match?.printing?.name) }
                if (added != null) _accepted.tryEmit(added)
            } catch (e: Exception) {
                Log.w(TAG, "frame failed", e)
            } finally {
                card.recycle()
                busy.set(false)
            }
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
