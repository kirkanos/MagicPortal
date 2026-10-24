package net.kirkanos.mtgportal.scanner.hash

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same files, same bits as dhash_test.go in the backend. */
class DHashTest {
    private class Pgm(val w: Int, val h: Int, val pix: ByteArray)

    private fun pgm(name: String): Pgm {
        val data = javaClass.getResource("/dhash/$name")!!.readBytes()
        // Header: "P5" w h 255, each followed by one whitespace character.
        var pos = 0
        fun token(): String {
            while (data[pos].toInt().toChar().isWhitespace()) pos++
            val start = pos
            while (!data[pos].toInt().toChar().isWhitespace()) pos++
            return String(data, start, pos - start)
        }
        check(token() == "P5")
        val w = token().toInt()
        val h = token().toInt()
        check(token() == "255")
        pos++
        return Pgm(w, h, data.copyOfRange(pos, pos + w * h))
    }

    private val cases by lazy {
        val arr = JSONArray(javaClass.getResource("/dhash/cases.json")!!.readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun matchesTheBackendBitForBit() {
        for (c in cases) {
            val p = pgm(c.getString("file"))
            val hash = DHash.of(p.pix, p.w, p.h)
            assertEquals(c.getString("file") + " h", c.getString("h"), "%016x".format(hash.h))
            assertEquals(c.getString("file") + " v", c.getString("v"), "%016x".format(hash.v))
        }
    }

    @Test
    fun grayIsTheBackendsFormula() {
        // (299r + 587g + 114b + 500) / 1000, as toGray in dhash.go
        val g = DHash.gray(intArrayOf(0xffffffff.toInt(), 0xff000000.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xff808080.toInt()))
        assertEquals(listOf(255, 0, 76, 150, 29, 128), g.map { it.toInt() and 0xff })
    }

    @Test
    fun differentCardsAreFarApart() {
        val hashes = cases.map { c -> pgm(c.getString("file")).let { DHash.of(it.pix, it.w, it.h) } }
        for (i in hashes.indices) for (j in i + 1 until hashes.size) {
            assertTrue("$i/$j", hashes[i].distance(hashes[j]) >= 40)
        }
    }
}
