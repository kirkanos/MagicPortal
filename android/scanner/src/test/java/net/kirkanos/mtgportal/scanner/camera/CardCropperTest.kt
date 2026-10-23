package net.kirkanos.mtgportal.scanner.camera

import net.kirkanos.mtgportal.scanner.hash.DHash
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.abs

class CardCropperTest {
    private class Gray(val w: Int, val h: Int, val px: ByteArray)

    private fun load(name: String): Gray {
        val img = ImageIO.read(javaClass.getResource("/frames/$name")!!)
        return Gray(img.width, img.height, DHash.gray(img.getRGB(0, 0, img.width, img.height, null, 0, img.width)))
    }

    private fun near(p: Pt, x: Float, y: Float, tol: Float = 8f) = abs(p.x - x) < tol && abs(p.y - y) < tol

    private fun box(b: Box) = Quad(Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom))

    /**
     * A German Victual Sliver, hand-held, a little tilted and larger than the
     * guide; corners read off the frame by eye. The cropper must take the
     * outer cut, not the gold frame inside the black border.
     */
    @Test
    fun findsTheOuterCutOfARealCard() {
        val g = load("victual_sliver_de.jpg")
        val q = CardCropper.find(g.px, g.w, g.h, GuideFrame.rect(g.w.toFloat(), g.h.toFloat()))
        assertNotNull(q)
        q!!
        assertTrue("tl $q", near(q.tl, 15f, 20f))
        assertTrue("tr $q", near(q.tr, 409f, 48f))
        assertTrue("br $q", near(q.br, 431f, 616f))
        assertTrue("bl $q", near(q.bl, 0f, 642f))
    }

    /** The point of it all: straightened, the card hashes close to its scan. */
    @Test
    fun straightenedCardsHashCloseToTheScan() {
        val scan = load("sth_131_scan.jpg").let { DHash.of(it.px, it.w, it.h) }
        for (f in listOf("victual_sliver_de.jpg", "victual_sliver_de_low.jpg")) {
            val g = load(f)
            val guide = GuideFrame.rect(g.w.toFloat(), g.h.toFloat())
            val q = CardCropper.find(g.px, g.w, g.h, guide)!!
            val cut = DHash.of(CardCropper.warp(g.px, g.w, g.h, q, 292, 408), 292, 408)
            val plain = DHash.of(CardCropper.warp(g.px, g.w, g.h, box(guide), 292, 408), 292, 408)
            assertTrue("$f: ${cut.distance(scan)}", cut.distance(scan) <= 30)
            assertTrue("$f: cropper ${cut.distance(scan)} vs guide ${plain.distance(scan)}", cut.distance(scan) < plain.distance(scan) - 10)
        }
    }

    /** Drawn card: light table, black border, gold frame inside. */
    @Test
    fun syntheticCardWithInnerFrame() {
        val w = 480
        val h = 640
        val px = ByteArray(w * h) { 200.toByte() }
        fun fill(l: Int, t: Int, r: Int, b: Int, v: Int) {
            for (y in t until b) for (x in l until r) px[y * w + x] = v.toByte()
        }
        // Card 30..440 x 40..613 (0.716), border 18 px, frame and art inside.
        fill(30, 40, 440, 613, 20)
        fill(48, 58, 422, 595, 150)
        fill(70, 100, 400, 330, 90)
        val q = CardCropper.find(px, w, h, GuideFrame.rect(w.toFloat(), h.toFloat()))!!
        assertTrue("$q", near(q.tl, 30f, 40f, 3f) && near(q.br, 440f, 613f, 3f))
    }

    @Test
    fun anEmptyTableIsNoCard() {
        val px = ByteArray(480 * 640) { 180.toByte() }
        assertNull(CardCropper.find(px, 480, 640, GuideFrame.rect(480f, 640f)))
    }
}
