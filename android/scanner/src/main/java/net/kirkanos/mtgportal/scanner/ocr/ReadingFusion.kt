package net.kirkanos.mtgportal.scanner.ocr

/**
 * Combines what consecutive frames read about the same card.
 *
 * On a hand-held card one frame often gets the title sharp and the next one
 * the collector line, rarely both (seen on the device: "Quicksand" in one
 * frame, "140/145" in the next). Missing fields are filled from the readings
 * of the last [windowMs]; fields a frame did read always win.
 *
 * A frame that read nothing identifying stays empty, so taking the card away
 * still ends the scanner's cooldown at once. A field inherited from the
 * previous card is harmless: the matcher checks name against number and
 * drops combinations the catalog does not know.
 */
class ReadingFusion(
    private val windowMs: Long = 1500,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val recent = ArrayDeque<Pair<Long, CardReading>>()

    fun fuse(r: CardReading): CardReading {
        val t = now()
        while (recent.isNotEmpty() && t - recent.first().first > windowMs) recent.removeFirst()
        if (r.name == null && r.setCode == null && r.numbers.isEmpty()) return r
        val past = recent.map { it.second }.asReversed()
        recent.addLast(t to r)
        return r.copy(
            name = r.name ?: past.firstNotNullOfOrNull { it.name },
            setCode = r.setCode ?: past.firstNotNullOfOrNull { it.setCode },
            language = r.language ?: past.firstNotNullOfOrNull { it.language },
            numbers = r.numbers.ifEmpty { past.firstOrNull { it.numbers.isNotEmpty() }?.numbers.orEmpty() },
            rarity = r.rarity ?: past.firstNotNullOfOrNull { it.rarity },
            year = r.year ?: past.firstNotNullOfOrNull { it.year },
        )
    }
}
