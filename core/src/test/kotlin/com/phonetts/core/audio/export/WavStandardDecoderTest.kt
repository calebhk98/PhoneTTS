package com.phonetts.core.audio.export

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the exported WAV is valid to a STANDARD audio decoder, not merely round-trippable by our
 * own [com.phonetts.core.audio.WavWriter]/reader. It runs the real [WavEncoder] end to end and then
 * hands the bytes to `javax.sound.sampled.AudioSystem` — the JVM's own WAV parser, the same class a
 * standard Java audio player uses — which throws on a malformed RIFF/`fmt `/`data` structure. This
 * is the guarantee the size>0 / duration>1s checks never gave: that a general-purpose player would
 * actually accept the file (the "fd://… can not be played" class of bug, issue #65).
 *
 * The file is also written to a stable path (`build/wavcheck/roundtrip.wav`) so it can be validated
 * out-of-process by other standard tools (Python's stdlib `wave`, ffprobe, etc.).
 */
class WavStandardDecoderTest {
    private val sampleRate = 22_050
    private val chunkSamples = 11_025 // 0.5 s per chunk
    private val chunkCount = 3 // 1.5 s total — comfortably over the 1 s floor

    @Test
    fun exportedWavIsReadableByAStandardDecoder() {
        val outDir = File("build/wavcheck").apply { mkdirs() }
        val wav = File(outDir, "roundtrip.wav")

        runBlocking {
            wav.outputStream().use { out ->
                WavEncoder().encode(sineChunks().asFlow(), sampleRate, out)
            }
        }

        assertTrue(wav.length() > HEADER_BYTES, "file is only a header / empty")

        // The independent standard decoder: it parses the container itself and fails on corruption.
        val fileFormat = AudioSystem.getAudioFileFormat(wav)
        assertEquals(AudioFileFormat.Type.WAVE, fileFormat.type, "not recognised as a WAVE file")

        AudioSystem.getAudioInputStream(wav).use { stream ->
            val format = stream.format
            assertEquals(sampleRate.toFloat(), format.sampleRate, "sample rate mismatch")
            assertEquals(1, format.channels, "expected mono")
            assertEquals(16, format.sampleSizeInBits, "expected 16-bit PCM")

            val expectedFrames = (chunkSamples * chunkCount).toLong()
            assertEquals(expectedFrames, stream.frameLength, "frame count doesn't match the audio written")

            // Fully decode every frame — a truncated/mis-sized data chunk surfaces here.
            val decoded = stream.readBytes().size.toLong()
            assertEquals(expectedFrames * format.frameSize, decoded, "decoded byte count doesn't match frames")
        }

        println("WAV standard-decoder check OK: ${wav.absolutePath} (${wav.length()} bytes)")
    }

    // A quiet 440 Hz sine split across [chunkCount] segments — mimics multi-sentence synthesis
    // arriving as separate flow emissions, which is what the streaming encoder must stitch together.
    private fun sineChunks(): List<FloatArray> =
        (0 until chunkCount).map { chunk ->
            FloatArray(chunkSamples) { i ->
                val t = (chunk * chunkSamples + i).toDouble() / sampleRate
                (0.25 * sin(2.0 * PI * 440.0 * t)).toFloat()
            }
        }

    private companion object {
        const val HEADER_BYTES = 44L
    }
}
