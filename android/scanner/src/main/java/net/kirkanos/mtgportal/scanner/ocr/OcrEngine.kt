package net.kirkanos.mtgportal.scanner.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One recognised line. Position and size as fractions of the card, so the
 * parsers neither depend on the image resolution nor on android.graphics
 * (and run as plain JVM tests).
 */
data class OcrLine(
    val text: String,
    /** Left edge, 0 = card's left border. */
    val x: Float,
    /** Vertical centre, 0 = top, 1 = bottom. */
    val y: Float,
    /** Line height as a fraction of the card height. */
    val h: Float,
)

/**
 * Thin wrapper around ML Kit's on-device Latin text recognizer. It gets the
 * card already cropped and upright, never the whole camera frame: less text
 * from the table around the card, and faster.
 */
class OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(card: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        val w = card.width.toFloat()
        val ht = card.height.toFloat()
        recognizer.process(InputImage.fromBitmap(card, 0))
            .addOnSuccessListener { text ->
                cont.resume(
                    text.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            line.boundingBox?.let { b -> OcrLine(line.text, b.left / w, b.exactCenterY() / ht, b.height() / ht) }
                        }
                    },
                )
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun close() = recognizer.close()
}
