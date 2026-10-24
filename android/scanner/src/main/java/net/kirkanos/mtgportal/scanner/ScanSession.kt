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
 * then, and garbage sometimes matches. A card is taken when it was seen in
 * [votes] of the last [window] frames. After that the same card is ignored
 * until it has left the window entirely - the card was taken away - so a
 * card lying under the camera counts once, and putting the next copy of it
 * down counts again.
 *
 * Votes go by card name, not printing: the printing of an old card can
 * change from frame to frame as the copyright year or number becomes
 * readable. Such a later, better match corrects the line just added instead
 * of adding a second one.
 */
class ScanSession(private val window: Int = 3, private val votes: Int = 2) {
    private val recent = ArrayDeque<String?>()
    private var lastAccepted: String? = null

    /** The line the last accepted card created, while it may still be corrected. */
    private var correctable: Long? = null
    private var nextId = 1L

    var items: List<SessionItem> = emptyList()
        private set

    /**
     * One analysed frame; [match] is null when nothing was recognised.
     * Returns the item that was added or counted up, if any.
     */
    fun offer(match: Match?, language: String?, foil: String = "normal"): SessionItem? {
        val key = match?.printing?.name
        recent.addLast(key)
        while (recent.size > window) recent.removeFirst()
        if (lastAccepted != null && lastAccepted !in recent) {
            lastAccepted = null
            correctable = null
        }
        if (match == null || key == null) return null
        if (key == lastAccepted) {
            correct(match)
            return null
        }
        if (recent.count { it == key } < votes) return null
        lastAccepted = key
        return add(match, language ?: "en", foil)
    }

    private fun add(match: Match, language: String, wantedFoil: String = "normal"): SessionItem {
        // The default finish only where the printing has it.
        val foil = match.printing.finishFor(wantedFoil)
        val same = items.firstOrNull { it.printing.i == match.printing.i && it.language == language && it.foil == foil }
        val item = same?.copy(quantity = same.quantity + 1)
            ?: SessionItem(nextId++, match.printing, match.alternatives, match.source, match.confidence, language, foil)
        // Only a line of its own can be corrected; a count added to an
        // existing line stays where it is.
        correctable = if (same == null) item.id else null
        // Newest on top, where the user looks after each card.
        items = listOf(item) + items.filter { it.id != item.id }
        return item
    }

    private fun correct(match: Match) {
        val id = correctable ?: return
        val item = items.firstOrNull { it.id == id } ?: return
        if (match.confidence <= item.confidence || match.printing.i == item.printing.i) return
        // The corrected printing may already have a line: count it there.
        val twin = items.firstOrNull { it.id != id && it.printing.i == match.printing.i && it.language == item.language && it.foil == item.foil }
        if (twin != null) {
            items = listOf(twin.copy(quantity = twin.quantity + item.quantity)) + items.filter { it.id != id && it.id != twin.id }
            correctable = null
            return
        }
        update(id) {
            it.copy(
                printing = match.printing,
                foil = match.printing.finishFor(it.foil),
                alternatives = (listOf(it.printing) + match.alternatives).filter { p -> p.i != match.printing.i }.distinctBy { p -> p.i },
                source = match.source,
                confidence = match.confidence,
            )
        }
    }

    /** A card the user picked by hand: no voting, counted like a scan. */
    fun addManual(printing: Printing, alternatives: List<Printing>, language: String = "en", foil: String = "normal"): SessionItem =
        add(Match(printing, 1f, MatchSource.MANUAL, alternatives), language, foil)

    fun update(id: Long, change: (SessionItem) -> SessionItem) {
        items = items.map { if (it.id == id) change(it) else it }
    }

    fun remove(id: Long) {
        items = items.filter { it.id != id }
    }

    fun result() = ScanSessionResult(items.map(SessionItem::toScannedCard))
}
