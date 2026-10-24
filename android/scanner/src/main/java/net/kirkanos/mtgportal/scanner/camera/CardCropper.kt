package net.kirkanos.mtgportal.scanner.camera

import kotlin.math.abs

/** A point in pixels. */
data class Pt(val x: Float, val y: Float)

/** The card's four corners, clockwise from top left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    fun scale(f: Float) = Quad(Pt(tl.x * f, tl.y * f), Pt(tr.x * f, tr.y * f), Pt(br.x * f, br.y * f), Pt(bl.x * f, bl.y * f))
}

/**
 * Finds the card's edges near the guide frame and straightens it.
 *
 * The image hash compares a fixed box of the artwork, and a card lying 3% off
 * the guide already moves the hash by 20 of 128 bits - measured on the first
 * camera images. So the card is located properly: for each side, every
 * straight line within a band around the guide edge is scored by the
 * brightness step across it, tilted lines included, and the four winners are
 * intersected into a quadrilateral that is then warped to 63:88.
 *
 * A card has several parallel edges - the outer cut and, a few percent
 * inside, the border meeting the frame. Of the lines nearly as strong as the
 * best one the outermost wins, which is the cut.
 *
 * Works on a small grayscale copy (about 400 px across the card); plain
 * arrays, so it runs in JVM tests on recorded camera frames.
 */
object CardCropper {
    /** How far a side may lie from the guide edge, as a fraction of the card width. */
    private const val BAND = 0.16f
    /** Candidate lines per side. */
    private const val PER_SIDE = 4
    /** A card's outer cut is weaker than its inner edges (shadow, blur), but not this weak. */
    private const val STRONG_EDGE = 18f
    /** Candidates of one side differ by at least this, as a fraction of the card width. */
    private const val SEPARATION = 0.025f
    /** Mean brightness step below which a line is no edge at all. */
    private const val MIN_EDGE = 8f

    /**
     * [gray]: w*h luminance values. [guide]: the guide frame in these pixels.
     * Null when no plausible card outline is found; the caller falls back to
     * the guide frame.
     */
    fun find(gray: ByteArray, w: Int, h: Int, guide: Box): Quad? {
        val m = (guide.width * BAND).toInt().coerceAtLeast(4)
        val lefts = candidates(gray, w, h, guide, m, vertical = true, at = guide.left)
        val rights = candidates(gray, w, h, guide, m, vertical = true, at = guide.right)
        val tops = candidates(gray, w, h, guide, m, vertical = false, at = guide.top)
        val bottoms = candidates(gray, w, h, guide, m, vertical = false, at = guide.bottom)
        val cx = (guide.left + guide.right) / 2
        val cy = (guide.top + guide.bottom) / 2
        // A side cut off by the image border has no candidates: the card's
        // shape gives it from the opposite side.
        val missing = listOf(lefts, rights, tops, bottoms).count { it.isEmpty() }
        if (missing > 1) return null

        var best: Quad? = null
        var bestScore = Float.NEGATIVE_INFINITY
        val none = listOf<Line?>(null)
        for (l in lefts.ifEmpty { none }) for (r in rights.ifEmpty { none }) for (t in tops.ifEmpty { none }) for (b in bottoms.ifEmpty { none }) {
            var left = l
            var right = r
            var top = t
            var bottom = b
            if (left == null || right == null) {
                val width = (bottom!!.at(cx) - top!!.at(cx)) * GuideFrame.CARD_RATIO
                if (left == null) left = right!!.shift(-width) else right = left.shift(width)
            } else if (top == null || bottom == null) {
                val height = (right.at(cy) - left.at(cy)) / GuideFrame.CARD_RATIO
                if (top == null) top = bottom!!.shift(-height) else bottom = top.shift(height)
            }
            val q = Quad(cross(left, top), cross(right, top), cross(right, bottom), cross(left, bottom))
            val shape = shape(q, guide) ?: continue
            // Of the card-shaped outlines with real edges, the largest: the
            // inner frame of an old card is as card-shaped as the card and
            // has the stronger edges, but it is never the outermost.
            val sides = listOfNotNull(l, r, t, b)
            val real = sides.all { it.score >= STRONG_EDGE }
            val score = (if (real) 1000f else 0f) + shape.area * 100f + sides.map { it.score }.average().toFloat() * 0.1f
            if (score > bestScore) {
                bestScore = score; best = q
            }
        }
        return best
    }

    /**
     * A side as a line through two points: for vertical sides x at the top
     * and bottom of the guide, for horizontal sides y at its left and right.
     */
    private class Line(val vertical: Boolean, val a: Float, val b: Float, val from: Float, val to: Float, val score: Float = 0f) {
        /** The coordinate across the line at position [t] along it. */
        fun at(t: Float) = a + (b - a) * (t - from) / (to - from)

        fun shift(d: Float) = Line(vertical, a + d, b + d, from, to, score)
    }

    /**
     * The strongest few lines near one guide edge, at least [SEPARATION] of
     * the card width apart: the outer cut, the border's inner edge, a text
     * box - the combination decides which is which.
     */
    private fun candidates(gray: ByteArray, w: Int, h: Int, g: Box, m: Int, vertical: Boolean, at: Float): List<Line> {
        // Sample along the middle 80% of the side: the corners are rounded.
        val from = if (vertical) g.top + g.height * 0.1f else g.left + g.width * 0.1f
        val to = if (vertical) g.bottom - g.height * 0.1f else g.right - g.width * 0.1f
        val limit = if (vertical) w else h
        val c = at.toInt()
        val lo = (c - m).coerceAtLeast(1)
        val hi = (c + m).coerceAtMost(limit - 2)
        if (hi <= lo) return emptyList()
        val n = hi - lo + 1
        val scores = FloatArray(n * n)
        for (i in 0 until n) for (j in 0 until n) {
            scores[i * n + j] = score(gray, w, h, vertical, (lo + i).toFloat(), (lo + j).toFloat(), from, to)
        }
        val order = scores.indices.sortedByDescending { scores[it] }
        val minGap = g.width * SEPARATION
        val picked = mutableListOf<Line>()
        for (k in order) {
            if (scores[k] < MIN_EDGE || picked.size == PER_SIDE) break
            val a = (lo + k / n).toFloat()
            val b = (lo + k % n).toFloat()
            if (picked.any { abs((it.a + it.b) / 2 - (a + b) / 2) < minGap }) continue
            picked += Line(vertical, a, b, from, to, scores[k])
        }
        return picked
    }

    /** Mean brightness step across the line, sampled every other pixel along it. */
    private fun score(g: ByteArray, w: Int, h: Int, vertical: Boolean, a: Float, b: Float, from: Float, to: Float): Float {
        var sum = 0
        var cnt = 0
        var t = from
        while (t <= to) {
            val across = (a + (b - a) * (t - from) / (to - from)).toInt()
            val along = t.toInt()
            sum += if (vertical) {
                val row = along * w
                abs((g[row + across + 1].toInt() and 0xff) - (g[row + across - 1].toInt() and 0xff))
            } else {
                abs((g[(across + 1) * w + along].toInt() and 0xff) - (g[(across - 1) * w + along].toInt() and 0xff))
            }
            cnt++
            t += 2f
        }
        return if (cnt == 0) 0f else sum.toFloat() / cnt
    }

    /** Where a vertical and a horizontal side meet. */
    private fun cross(v: Line, hz: Line): Pt {
        // v: x = v.at(y); hz: y = hz.at(x). Two rounds of substitution settle
        // it for the small tilts a held card has.
        var y = (hz.a + hz.b) / 2
        var x = v.at(y)
        repeat(3) {
            y = hz.at(x)
            x = v.at(y)
        }
        return Pt(x, y)
    }

    private class Shape(val ratioError: Float, val area: Float)

    /** How card-like a quadrilateral is; null when not at all. */
    private fun shape(q: Quad, g: Box): Shape? {
        val wTop = dist(q.tl, q.tr)
        val wBot = dist(q.bl, q.br)
        val hL = dist(q.tl, q.bl)
        val hR = dist(q.tr, q.br)
        val ratioError = abs((wTop + wBot) / (hL + hR) - GuideFrame.CARD_RATIO)
        val area = (wTop + wBot) / 2 * (hL + hR) / 2 / (g.width * g.height)
        if (ratioError > 0.08f || area < 0.6f || area > 1.6f) return null
        return Shape(ratioError, area)
    }

    private fun dist(a: Pt, b: Pt) = kotlin.math.hypot(a.x - b.x, a.y - b.y)

    /**
     * The quadrilateral warped to an upright [outW] x [outH] image
     * (projective mapping, bilinear sampling).
     */
    fun warp(gray: ByteArray, w: Int, h: Int, q: Quad, outW: Int, outH: Int): ByteArray {
        val m = squareToQuad(q)
        val out = ByteArray(outW * outH)
        for (oy in 0 until outH) {
            val v = (oy + 0.5f) / outH
            for (ox in 0 until outW) {
                val u = (ox + 0.5f) / outW
                val den = m[6] * u + m[7] * v + 1f
                val x = (m[0] * u + m[1] * v + m[2]) / den - 0.5f
                val y = (m[3] * u + m[4] * v + m[5]) / den - 0.5f
                out[oy * outW + ox] = bilinear(gray, w, h, x, y).toByte()
            }
        }
        return out
    }

    private fun bilinear(g: ByteArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, w - 2)
        val y0 = y.toInt().coerceIn(0, h - 2)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        fun p(xx: Int, yy: Int) = (g[yy * w + xx].toInt() and 0xff).toFloat()
        val top = p(x0, y0) * (1 - fx) + p(x0 + 1, y0) * fx
        val bot = p(x0, y0 + 1) * (1 - fx) + p(x0 + 1, y0 + 1) * fx
        return (top * (1 - fy) + bot * fy + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Heckbert's unit square to quadrilateral mapping: x = (a u + b v + c) / (g u + h v + 1), y likewise. */
    private fun squareToQuad(q: Quad): FloatArray {
        val (x0, y0) = q.tl
        val (x1, y1) = q.tr
        val (x2, y2) = q.br
        val (x3, y3) = q.bl
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        if (abs(sx) < 1e-6f && abs(sy) < 1e-6f) {
            return floatArrayOf(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0f, 0f)
        }
        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val den = dx1 * dy2 - dx2 * dy1
        val g = (sx * dy2 - dx2 * sy) / den
        val hh = (dx1 * sy - sx * dy1) / den
        return floatArrayOf(
            x1 - x0 + g * x1, x3 - x0 + hh * x3, x0,
            y1 - y0 + g * y1, y3 - y0 + hh * y3, y0,
            g, hh,
        )
    }
}
