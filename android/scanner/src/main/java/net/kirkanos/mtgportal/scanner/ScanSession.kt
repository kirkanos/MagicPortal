package net.kirkanos.mtgportal.scanner

import net.kirkanos.mtgportal.scanner.catalog.Printing
import net.kirkanos.mtgportal.scanner.match.Match

/** One line of the session list: a printing in a language and finish, with a count. */
data class SessionItem(
    val id: Long,
    val printing: Printing,
    val alternatives: List<Printing>,
    val source: MatchSource,
    val confidence: Float,
    val language: String = "en",
    val foil: String = "normal",
    val quantity: Int = 1,
) {
    /** Name-only matches pick a printing by year; the user should look. */
    val uncertain get() = confidence < 0.6f

    fun toScannedCard() = ScannedCard(
        setCode = printing.setCode,
        collectorNumber = printing.collectorNumber,
        scryfallId = printing.scryfallId,
        name = printing.name,
        foil = foil,
        language = language,
        quantity = quantity,
        source = source,
        confidence = confidence,
    )
}

/**
 * Turns a stream of per-frame matches into scanned cards.
 *
 * A single frame is not trusted: OCR on a moving card reads garbage now and
 * then, and garbage sometimes matches. A printing is taken when it was seen
 * in [votes] of the last [window] frames. After that the same printing is
 * ignored until it has left the window entirely - the card was taken away -
 * so a card lying under the camera counts once, and putting the next copy of
 * it down counts again.
 */
class ScanSession(private val window: Int = 3, private val votes: Int = 2) {
    private val recent = ArrayDeque<Int?>()
    private var lastAccepted: Int? = null
    private var nextId = 1L

    var items: List<SessionItem> = emptyList()
        private set

    /**
     * One analysed frame; [match] is null when nothing was recognised.
     * Returns the item that was added or counted up, if any.
     */
    fun offer(match: Match?, language: String?): SessionItem? {
        val key = match?.printing?.i
        recent.addLast(key)
        while (recent.size > window) recent.removeFirst()
        if (lastAccepted != null && lastAccepted !in recent) lastAccepted = null
        if (key == null || key == lastAccepted || recent.count { it == key } < votes) return null
        lastAccepted = key
        return add(match, language ?: "en")
    }

    private fun add(match: Match, language: String): SessionItem {
        val same = items.firstOrNull { it.printing.i == match.printing.i && it.language == language && it.foil == "normal" }
        val item = same?.copy(quantity = same.quantity + 1)
            ?: SessionItem(nextId++, match.printing, match.alternatives, match.source, match.confidence, language)
        // Newest on top, where the user looks after each card.
        items = listOf(item) + items.filter { it.id != item.id }
        return item
    }

    fun update(id: Long, change: (SessionItem) -> SessionItem) {
        items = items.map { if (it.id == id) change(it) else it }
    }

    fun remove(id: Long) {
        items = items.filter { it.id != id }
    }

    fun result() = ScanSessionResult(items.map(SessionItem::toScannedCard))
}
