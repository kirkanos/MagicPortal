package net.kirkanos.mtgportal.scanner

import androidx.compose.runtime.Composable

/**
 * The seam between the app and the camera scanner.
 *
 * :app only knows this interface; the implementation, its camera permission
 * and its dependencies live in this module. While no implementation is wired
 * up, the app hides the scanner entry.
 *
 * The scanner never writes to the backend. It hands recognised cards to the
 * caller, which owns binder, condition and the write itself.
 */
interface ScannerEntry {
    @Composable
    fun Screen(onResult: (ScanSessionResult) -> Unit, onClose: () -> Unit)
}

enum class MatchSource { OCR_SET_NUMBER, OCR_NAME, HASH, MANUAL }

/** One recognised printing, in the collection's vocabulary. */
data class ScannedCard(
    /** Lowercase Scryfall set code, the way the backend keys on it. */
    val setCode: String,
    /** Scryfall form: no leading zeros, suffix kept ("161", "161a"). */
    val collectorNumber: String,
    val scryfallId: String,
    /** Catalog name (English, "Front // Back" for double-faced cards). */
    val name: String,
    /** normal | foil | etched */
    val foil: String = "normal",
    /** en, de, fr, it, es, pt, ja, ko, ru, zhs, zht */
    val language: String = "en",
    val quantity: Int = 1,
    val source: MatchSource,
    /** 0..1, informational only. */
    val confidence: Float,
)

data class ScanSessionResult(val items: List<ScannedCard>)
