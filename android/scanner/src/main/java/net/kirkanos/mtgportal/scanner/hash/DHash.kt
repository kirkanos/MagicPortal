package net.kirkanos.mtgportal.scanner.hash

/** A 128-bit difference hash: horizontal and vertical half. */
data class CardHash(val h: Long, val v: Long) {
    fun distance(o: CardHash) = java.lang.Long.bitCount(h xor o.h) + java.lang.Long.bitCount(v xor o.v)
    fun distance(oh: Long, ov: Long) = java.lang.Long.bitCount(h xor oh) + java.lang.Long.bitCount(v xor ov)
}

/**
 * Twin of backend/dhash.go - same box, same cells, same bits; the shared
 * cases in testdata/dhash keep the two equal. The backend hashes Scryfall's
 * scans, the phone the camera image of the card, and the scanner looks for
 * the nearest one.
 *
 * Covers an inset box of the artwork, x in [10%, 90%), y in [12%, 50%) of the
 * card. Integer arithmetic only, so the JVM and Go agree to the bit.
 */
object DHash {
    private const val X0 = 10
    private const val X1 = 90
    private const val Y0 = 12
    private const val Y1 = 50

    /** BT.601 luminance with integer weights, as toGray in Go. */
    fun gray(argb: IntArray): ByteArray = ByteArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
    }

    /** [gray] is w*h luminance values, row by row (unsigned bytes). */
    fun of(gray: ByteArray, w: Int, h: Int): CardHash {
        val hc = cells(gray, w, h, 9, 8)
        var hh = 0L
        for (r in 0 until 8) for (c in 0 until 8) {
            if (hc[r * 9 + c + 1] > hc[r * 9 + c]) hh = hh or (1L shl (63 - (r * 8 + c)))
        }
        val vc = cells(gray, w, h, 8, 9)
        var vv = 0L
        for (r in 0 until 8) for (c in 0 until 8) {
            if (vc[(r + 1) * 8 + c] > vc[r * 8 + c]) vv = vv or (1L shl (63 - (r * 8 + c)))
        }
        return CardHash(hh, vv)
    }

    private fun cells(g: ByteArray, w: Int, h: Int, cols: Int, rows: Int): IntArray {
        val x0 = w * X0 / 100
        val x1 = w * X1 / 100
        val y0 = h * Y0 / 100
        val y1 = h * Y1 / 100
        val bw = x1 - x0
        val bh = y1 - y0
        val out = IntArray(cols * rows)
        for (r in 0 until rows) {
            val ya = y0 + r * bh / rows
            val yb = y0 + (r + 1) * bh / rows
            for (c in 0 until cols) {
                val xa = x0 + c * bw / cols
                val xb = x0 + (c + 1) * bw / cols
                var sum = 0
                var n = 0
                for (y in ya until yb) {
                    val row = y * w
                    for (x in xa until xb) {
                        sum += g[row + x].toInt() and 0xff
                        n++
                    }
                }
                if (n > 0) out[r * cols + c] = sum / n
            }
        }
        return out
    }
}
