package net.kirkanos.mtgportal.scanner

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.kirkanos.mtgportal.scanner.ocr.OcrEngine
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion test: logs what ML Kit reads from each fixture card as one
 * JSON line per card. That output is the ground truth the parsers are written
 * against, and it is copied into the JVM test fixtures (ocr_fixtures.json).
 *
 *   ./gradlew :scanner:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=net.kirkanos.mtgportal.scanner.OcrDumpTest
 *   adb logcat -d -s OcrDump
 */
@RunWith(AndroidJUnit4::class)
class OcrDumpTest {
    @Test
    fun dump() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val engine = OcrEngine()
        for (file in ctx.assets.list("cards")!!.sorted()) {
            val bmp = ctx.assets.open("cards/$file").use { BitmapFactory.decodeStream(it) }
            val lines = engine.read(bmp).sortedBy { it.y }.joinToString(",") { l ->
                val t = l.text.replace("\\", "\\\\").replace("\"", "\\\"")
                String.format(java.util.Locale.US, "{\"t\":\"%s\",\"x\":%.3f,\"y\":%.3f,\"h\":%.3f}", t, l.x, l.y, l.h)
            }
            Log.i("OcrDump", "{\"file\":\"$file\",\"lines\":[$lines]}")
        }
        engine.close()
    }
}
