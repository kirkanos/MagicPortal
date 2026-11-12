package net.kirkanos.mtgportal.scanner

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The sound of a scanned card, by what it is worth - like ManaBox, the ear
 * tells a bulk card from a good one without looking: one short blip, then
 * longer rising chimes for more valuable cards.
 *
 * The tones are synthesised once (a few KB each) and played from static
 * tracks; on the media stream, so the volume keys set them and the ring mode
 * of the phone does not silence them.
 */
class ScanSounds {
    private val tracks: List<AudioTrack?> = TIERS.map { notes -> runCatching { track(render(notes)) }.getOrNull() }

    fun play(price: Double?) {
        val t = tracks.getOrNull(tier(price)) ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    fun release() = tracks.forEach { it?.release() }

    private fun track(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    /** A note: frequency in Hz, start and length in ms. */
    private data class Note(val hz: Double, val at: Int, val ms: Int)

    companion object {
        private const val RATE = 44_100

        /** Lower bounds in euros of tiers 1, 2 and 3; tier 0 is below, or no price. */
        val THRESHOLDS = listOf(1.0, 5.0, 20.0)

        fun tier(price: Double?): Int = if (price == null) 0 else THRESHOLDS.count { price >= it }

        private const val C6 = 1046.5
        private const val E6 = 1318.5
        private const val G6 = 1568.0
        private const val C7 = 2093.0

        private val TIERS = listOf(
            listOf(Note(C6, 0, 70)),
            listOf(Note(C6, 0, 80), Note(G6, 80, 140)),
            listOf(Note(C6, 0, 80), Note(E6, 80, 80), Note(G6, 160, 200)),
            listOf(Note(C6, 0, 70), Note(E6, 70, 70), Note(G6, 140, 70), Note(C7, 210, 420)),
        )

        /**
         * Sine with a little second harmonic (bell-like rather than a test
         * tone), a 4 ms attack against clicks and an exponential decay.
         */
        private fun render(notes: List<Note>): ShortArray {
            val total = notes.maxOf { it.at + it.ms } * RATE / 1000
            val mix = DoubleArray(total)
            for (n in notes) {
                val start = n.at * RATE / 1000
                val len = n.ms * RATE / 1000
                for (i in 0 until min(len, total - start)) {
                    val t = i.toDouble() / RATE
                    val attack = min(1.0, t / 0.004)
                    val env = attack * exp(-t * 5.0 / (n.ms / 1000.0))
                    mix[start + i] += env * (sin(2 * PI * n.hz * t) + 0.3 * sin(4 * PI * n.hz * t))
                }
            }
            return ShortArray(total) { i -> (mix[i].coerceIn(-1.3, 1.3) / 1.3 * 0.7 * Short.MAX_VALUE).toInt().toShort() }
        }
    }
}
