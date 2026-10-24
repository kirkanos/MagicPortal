package net.kirkanos.mtgportal.scanner.hash

import net.kirkanos.mtgportal.scanner.catalog.CatalogIndex
import java.nio.ByteBuffer

/** A printing (index into [CatalogIndex]) and its hash distance to the camera image. */
data class HashHit(val printing: Int, val distance: Int)

/**
 * The backend's image hashes (/api/catalog/hashes), tied to the catalog's
 * printings. A linear scan over plain arrays: 27,000 entries are two XORs
 * and popcounts each, well under a millisecond.
 */
class HashIndex private constructor(
    private val printings: IntArray,
    private val h: LongArray,
    private val v: LongArray,
) {
    val size get() = printings.size

    private val byPrinting: Map<Int, Int> by lazy { printings.withIndex().associate { (i, p) -> p to i } }

    /** Distance of [hash] to one printing's scan, null when it has no hash. */
    fun distance(hash: CardHash, printing: Int): Int? = byPrinting[printing]?.let { hash.distance(h[it], v[it]) }

    /** The [k] nearest printings that pass [accept], closest first. */
    fun nearest(hash: CardHash, k: Int = 20, accept: (Int) -> Boolean = { true }): List<HashHit> {
        val d = IntArray(printings.size) { hash.distance(h[it], v[it]) }
        return d.indices.filter { accept(printings[it]) }.sortedBy { d[it] }.take(k).map { HashHit(printings[it], d[it]) }
    }

    companion object {
        /**
         * "MPH1", uint32 count, then per printing a 16-byte Scryfall ID and
         * the two hash halves, big-endian. Hashes of printings the catalog
         * does not know (yet) are dropped.
         */
        fun parse(data: ByteArray, catalog: CatalogIndex): HashIndex {
            val buf = ByteBuffer.wrap(data)
            require(data.size >= 8 && String(data, 0, 4) == "MPH1") { "not a hash index" }
            buf.position(4)
            val n = buf.int
            val ps = IntArray(n)
            val hs = LongArray(n)
            val vs = LongArray(n)
            var k = 0
            repeat(n) {
                val hi = buf.long
                val lo = buf.long
                val hh = buf.long
                val vv = buf.long
                val p = catalog.indexOfId(uuid(hi, lo)) ?: return@repeat
                ps[k] = p; hs[k] = hh; vs[k] = vv
                k++
            }
            return HashIndex(ps.copyOf(k), hs.copyOf(k), vs.copyOf(k))
        }

        private fun uuid(hi: Long, lo: Long) = java.util.UUID(hi, lo).toString()
    }
}
