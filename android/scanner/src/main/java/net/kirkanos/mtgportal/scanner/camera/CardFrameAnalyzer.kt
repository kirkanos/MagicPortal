package net.kirkanos.mtgportal.scanner.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

/** A rectangle in pixels; plain floats so the geometry is testable on the JVM. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Where the card should lie: a 63:88 frame centred in the visible camera
 * area. The overlay draws it in the preview's pixels, the analyzer crops it
 * from the frame's pixels; both describe the same viewport, so one function
 * serves both.
 */
object GuideFrame {
    const val CARD_RATIO = 63f / 88f

    fun rect(w: Float, h: Float): Box {
        val cw = minOf(w * 0.86f, h * 0.88f * CARD_RATIO)
        val ch = cw / CARD_RATIO
        val l = (w - cw) / 2
        val t = (h - ch) / 2
        return Box(l, t, l + cw, t + ch)
    }
}

/**
 * Hands over the visible part of each camera frame (the crop rect CameraX
 * sets from the preview's viewport), turned upright; the guide frame is cut
 * from it by the caller. Frames arriving while the previous one is still
 * being read are dropped right here, before any copying.
 */
class CardFrameAnalyzer(
    private val busy: AtomicBoolean,
    private val onFrame: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        image.use {
            if (!busy.compareAndSet(false, true)) return
            val frame = try {
                visible(image)
            } catch (e: RuntimeException) {
                busy.set(false)
                return
            }
            onFrame(frame)
        }
    }

    private fun visible(image: ImageProxy): Bitmap {
        val full = image.toBitmap()
        val c = image.cropRect
        val turn = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        val visible = Bitmap.createBitmap(full, c.left, c.top, c.width(), c.height(), turn, true)
        if (visible !== full) full.recycle()
        return visible
    }
}

/** The guide frame's part of a visible frame. */
fun cutCard(frame: Bitmap): Bitmap {
    val g = GuideFrame.rect(frame.width.toFloat(), frame.height.toFloat())
    return Bitmap.createBitmap(frame, g.left.toInt(), g.top.toInt(), g.width.toInt(), g.height.toInt())
}
