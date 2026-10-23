package net.kirkanos.mtgportal.scanner.camera

import net.kirkanos.mtgportal.scanner.hash.DHash
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Not a regular test: measures recorded camera frames against Scryfall scans.
 *
 *   SCANNER_FRAMES=<dir of dumped frames> SCANNER_REFS=<dir of small scans> \
 *     ./gradlew :scanner:testDebugUnitTest --tests '*HashEvalTest*' -i
 *
 * Prints, per frame, the hash distance to every reference with the plain
 * guide crop and with the card cropper, and writes the straightened card next
 * to the frames (*.warp.png) to look at.
 */
class HashEvalTest {
    private class Gray(val w: Int, val h: Int, val px: ByteArray)

    private fun gray(img: BufferedImage): Gray {
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return Gray(img.width, img.height, DHash.gray(px))
    }

    private fun scaled(img: BufferedImage, w: Int): BufferedImage {
        val h = img.height * w / img.width
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.graphics.drawImage(img.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null)
        return out
    }

    private fun boxQuad(b: Box) = Quad(Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom))

    @Test
    fun evaluate() {
        val frames = System.getenv("SCANNER_FRAMES")
        val refsDir = System.getenv("SCANNER_REFS")
        assumeTrue(frames != null && refsDir != null)
        val refs = File(refsDir).listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().associate { f ->
            val g = gray(ImageIO.read(f))
            f.nameWithoutExtension to DHash.of(g.px, g.w, g.h)
        }
        for (f in File(frames).listFiles { f -> f.name.endsWith(".jpg") }!!.sorted()) {
            val small = gray(scaled(ImageIO.read(f), 480))
            val guide = GuideFrame.rect(small.w.toFloat(), small.h.toFloat())
            val t0 = System.nanoTime()
            val quad = CardCropper.find(small.px, small.w, small.h, guide)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val plain = CardCropper.warp(small.px, small.w, small.h, boxQuad(guide), 292, 408)
            val cut = CardCropper.warp(small.px, small.w, small.h, quad ?: boxQuad(guide), 292, 408)
            val hp = DHash.of(plain, 292, 408)
            val hc = DHash.of(cut, 292, 408)
            println("== ${f.name}  cropper ${if (quad == null) "FAILED" else "ok"} in $ms ms  $quad")
            for ((name, r) in refs) println("   %-14s guide %3d   cropper %3d".format(name, hp.distance(r), hc.distance(r)))
            val png = BufferedImage(292, 408, BufferedImage.TYPE_BYTE_GRAY)
            png.raster.setDataElements(0, 0, 292, 408, cut)
            ImageIO.write(png, "png", File(f.path.removeSuffix(".jpg") + ".warp.png"))
        }
    }
}
