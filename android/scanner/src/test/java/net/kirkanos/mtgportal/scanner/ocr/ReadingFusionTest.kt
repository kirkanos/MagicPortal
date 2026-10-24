package net.kirkanos.mtgportal.scanner.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingFusionTest {
    private var t = 0L
    private val fusion = ReadingFusion(windowMs = 1500, now = { t })

    private fun r(name: String? = null, numbers: List<NumberCandidate> = emptyList(), year: Int? = null) =
        CardReading(name, null, null, numbers, null, year)

    @Test
    fun fillsTheMissingHalf() {
        fusion.fuse(r(name = "Quicksand"))
        t += 200
        val f = fusion.fuse(r(numbers = listOf(NumberCandidate("140", 145)), year = 2010))
        assertEquals("Quicksand", f.name)
        assertEquals("140", f.numbers.single().number)
    }

    @Test
    fun ownFieldsWin() {
        fusion.fuse(r(name = "Quicksand", numbers = listOf(NumberCandidate("140"))))
        t += 200
        assertEquals("Maze of Shadows", fusion.fuse(r(name = "Maze of Shadows")).name)
    }

    @Test
    fun anEmptyFrameStaysEmpty() {
        fusion.fuse(r(name = "Quicksand"))
        t += 200
        // The card was taken away; only a stray year was read.
        assertNull(fusion.fuse(r(year = 1998)).name)
    }

    @Test
    fun oldReadingsExpire() {
        fusion.fuse(r(name = "Quicksand"))
        t += 2000
        assertNull(fusion.fuse(r(numbers = listOf(NumberCandidate("140")))).name)
    }
}
