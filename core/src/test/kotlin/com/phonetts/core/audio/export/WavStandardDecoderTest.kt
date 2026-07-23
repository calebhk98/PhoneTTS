package com.phonetts.core.audio.export

import com.phonetts.core.audio.transform.LoudnessNormalize
import com.phonetts.core.audio.transform.SilenceTrim
import com.phonetts.core.audio.transform.TransformChain
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * hands the bytes to `javax.sound.sampled.AudioSystem` - the JVM's own WAV parser, the same class a
 * standard Java audio player uses - which throws on a malformed RIFF/`fmt `/`data` structure. This
 * is the guarantee the size>0 / duration>1s checks never gave: that a general-purpose player would
 * actually accept the file (the "fd://… can not be played" class of bug, issue #65).
 *
 * The file is also written to a stable path (`build/wavcheck/roundtrip.wav`) so it can be validated
 * out-of-process by other standard tools (Python's stdlib `wave`, ffprobe, etc.).
 */
class WavStandardDecoderTest {
    private val sampleRate = 22_050
    private val chunkSamples = 11_025 // 0.5 s per chunk
    private val chunkCount = 3 // 1.5 s total - comfortably over the 1 s floor

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

            // Fully decode every frame - a truncated/mis-sized data chunk surfaces here.
            val decoded = stream.readBytes().size.toLong()
            assertEquals(expectedFrames * format.frameSize, decoded, "decoded byte count doesn't match frames")
        }

        println("WAV standard-decoder check OK: ${wav.absolutePath} (${wav.length()} bytes)")
    }

    /**
     * Plays to completion, not "plays 1.499s then errors." A strict player plays the PCM and then
     * errors at the end when the header's size fields disagree with the actual bytes (the original
     * "error with the saved file, after playing it", issue #65). So this asserts the two size fields
     * are byte-exact AND that feeding every frame reaches a single clean end-of-stream (-1) with no
     * trailing partial frame - the structural guarantee that a player runs to the end without a tail
     * error. (Actual speaker output is an on-device check; there is no audio line in CI.)
     */
    @Test
    fun exportedWavPlaysToACleanEndInsteadOfErroringAtTheTail() {
        val outDir = File("build/wavcheck").apply { mkdirs() }
        val wav = File(outDir, "tail.wav")
        runBlocking {
            wav.outputStream().use { out -> WavEncoder().encode(sineChunks().asFlow(), sampleRate, out) }
        }

        val header = wav.inputStream().use { it.readNBytes(HEADER_BYTES.toInt()) }
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riffSize = le.getInt(RIFF_SIZE_OFFSET).toLong() and 0xffffffffL
        val dataSize = le.getInt(DATA_SIZE_OFFSET).toLong() and 0xffffffffL
        assertEquals(wav.length() - RIFF_SIZE_TRAILING, riffSize, "RIFF ChunkSize must match the file")
        assertEquals(wav.length() - HEADER_BYTES, dataSize, "data chunk size must match the PCM written")

        AudioSystem.getAudioInputStream(wav).use { stream ->
            val frameSize = stream.format.frameSize
            val buf = ByteArray(4096)
            var fed = 0L
            var read = stream.read(buf)
            while (read != -1) {
                fed += read
                read = stream.read(buf)
            }
            assertEquals(0L, fed % frameSize, "stream ended on a partial frame")
            assertEquals(stream.frameLength * frameSize, fed, "player fed fewer bytes than the clip")
            assertEquals(-1, stream.read(buf), "end-of-stream is not clean (dribble after EOF)")
        }
    }

    /**
     * The two tests above exercise [WavEncoder] with no [com.phonetts.core.audio.transform.TransformChain]
     * - but the real export path ([com.phonetts.app.ui.TtsViewModel.export]) always routes through
     * [AudioEncoder.encode]'s transform pipeline, even when every transform happens to be disabled. A
     * header/byte mismatch could in principle hide in that pipeline (e.g. a stage's `finish()` losing
     * or duplicating trailing samples) without either test above ever exercising it. This runs the same
     * clean-EOF/byte-exact assertions through a chain with BOTH kinds of stage enabled - an
     * [com.phonetts.core.audio.transform.IncrementalTransform] streaming stage ([LoudnessNormalize])
     * and the one full-buffer fallback
     * stage ([SilenceTrim], which also intentionally shortens the clip by trimming the sine's near-zero
     * leading/trailing samples) - so the header's data size is proven to still track whatever the
     * pipeline actually emits, not just the untransformed case.
     */
    @Test
    fun exportedWavWithTransformsAppliedStillPlaysToACleanEnd() {
        val outDir = File("build/wavcheck").apply { mkdirs() }
        val wav = File(outDir, "tail-with-transforms.wav")
        val transforms =
            TransformChain
                .of(listOf(LoudnessNormalize(), SilenceTrim()))
                .withEnabled(LoudnessNormalize.ID, true)
                .withEnabled(SilenceTrim.ID, true)

        runBlocking {
            wav.outputStream().use { out ->
                WavEncoder().encode(sineChunks().asFlow(), sampleRate, out, transforms)
            }
        }

        assertTrue(wav.length() > HEADER_BYTES, "file is only a header / empty")

        val header = wav.inputStream().use { it.readNBytes(HEADER_BYTES.toInt()) }
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riffSize = le.getInt(RIFF_SIZE_OFFSET).toLong() and 0xffffffffL
        val dataSize = le.getInt(DATA_SIZE_OFFSET).toLong() and 0xffffffffL
        assertEquals(wav.length() - RIFF_SIZE_TRAILING, riffSize, "RIFF ChunkSize must match the file")
        assertEquals(wav.length() - HEADER_BYTES, dataSize, "data chunk size must match the PCM actually written")

        AudioSystem.getAudioInputStream(wav).use { stream ->
            val frameSize = stream.format.frameSize
            val buf = ByteArray(4096)
            var fed = 0L
            var read = stream.read(buf)
            while (read != -1) {
                fed += read
                read = stream.read(buf)
            }
            assertEquals(0L, fed % frameSize, "stream ended on a partial frame")
            assertEquals(stream.frameLength * frameSize, fed, "player fed fewer bytes than the clip")
            assertEquals(-1, stream.read(buf), "end-of-stream is not clean (dribble after EOF)")
        }
    }

    // A quiet 440 Hz sine split across [chunkCount] segments - mimics multi-sentence synthesis
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
        const val RIFF_SIZE_OFFSET = 4 // "RIFF" is bytes 0-3; its 4-byte size follows
        const val DATA_SIZE_OFFSET = 40 // the "data" chunk's 4-byte size, at byte 40 in a canonical header
        const val RIFF_SIZE_TRAILING = 8L // RIFF ChunkSize excludes the "RIFF" tag + the size field itself
    }
}
