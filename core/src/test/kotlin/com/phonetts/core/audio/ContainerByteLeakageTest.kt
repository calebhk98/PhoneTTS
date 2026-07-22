package com.phonetts.core.audio

import com.phonetts.core.audio.buffer.BufferedPlayback
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.audio.buffer.collectInto
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.testing.FakeEngine
import com.phonetts.core.testing.testDescriptor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression guard for the classic "container bytes in the PCM stream" bug (issue #45): a WAV
 * header (the RIFF/WAVE/fmt/data framing) leaking into the `Flow<FloatArray>` that PLAYBACK
 * consumes, which shows up as a pop/click at the very start of playback.
 *
 * The design keeps ALL container framing on the export side ([WavWriter]/[WavEncoder]); the two
 * playback consumers ([StreamingConsumer] and [BufferedPlayback]) only ever see raw PCM samples,
 * because both drain the SAME `synthesize()` flow (spec §6.1) and that flow carries floats, never
 * encoded bytes. These tests pin that invariant so a future refactor can't quietly route encoded
 * bytes into the playback path. It is already correct today — the value here is the guard.
 */
class ContainerByteLeakageTest {
    private val engine =
        FakeEngine(
            id = "fake",
            audio =
                listOf(
                    floatArrayOf(0.05f, -0.05f, 0.5f),
                    floatArrayOf(-0.5f, 0.25f),
                    floatArrayOf(0.9f, -0.9f, 0.0f, 0.123f),
                ),
        )
    private val descriptor = testDescriptor(modelId = "m", engineId = "fake")

    // A fresh view of the ONE generation flow. FakeEngine emits a new flow each call, so every
    // consumer under test drains an independent copy of the exact same synthesized samples.
    private fun synth() = engine.synthesize("hi", descriptor.defaultVoiceId, SynthesisParams.DEFAULT)

    @Test
    fun streamingPlaybackReceivesOnlyRawSamplesNoContainerFraming() =
        runTest {
            val emitted = synth().toList()
            val rawSamples = emitted.flatMap { it.toList() }.toFloatArray()
            val sink = RecordingSink()

            StreamingConsumer().play(synth(), descriptor.sampleRate, sink)

            // Bit-identical to what the engine emitted: no header samples prepended/appended, and
            // exactly as many chunks as the engine produced (no extra "framing" chunk).
            assertContentEquals(rawSamples, sink.recorded)
            assertEquals(emitted.size, sink.chunkCount)
        }

    @Test
    fun bufferedPlaybackReceivesOnlyRawSamplesNoContainerFraming() =
        runTest {
            val rawSamples = synth().toList().flatMap { it.toList() }.toFloatArray()
            val audio = GeneratedAudio()
            synth().collectInto(audio)
            val sink = RecordingSink()

            BufferedPlayback().play(audio, descriptor.sampleRate, sink)

            assertContentEquals(rawSamples, sink.recorded)
        }

    @Test
    fun wavFramingLivesOnlyOnTheExportSideNeverInThePlaybackStream() =
        runTest {
            val out = ByteArrayOutputStream()
            WavWriter().write(synth(), descriptor.sampleRate, out)
            val wav = out.toByteArray()
            val parsed = parseWav(wav)

            // The framing exists on the EXPORT output...
            assertEquals("RIFF", parsed.riff)
            assertEquals("WAVE", parsed.wave)
            assertEquals("data", parsed.dataTag)

            // ...and the only difference between the exported bytes and the playback samples is
            // that 44-byte header: the WAV data section equals the playback PCM byte-for-byte, so
            // no framing byte ever reached the sink.
            val sink = RecordingSink()
            StreamingConsumer().play(synth(), descriptor.sampleRate, sink)
            val playbackPcm = Pcm16.encode(sink.recorded)
            assertContentEquals(wav.copyOfRange(WAV_HEADER_SIZE, wav.size), playbackPcm)

            // Belt and suspenders: none of the container tags appear anywhere in the playback bytes.
            assertFalse(containsAscii(playbackPcm, "RIFF"), "RIFF tag leaked into playback stream")
            assertFalse(containsAscii(playbackPcm, "WAVE"), "WAVE tag leaked into playback stream")
            assertFalse(containsAscii(playbackPcm, "data"), "data tag leaked into playback stream")
        }

    // True if [needle]'s ASCII bytes appear as a contiguous run anywhere in [haystack].
    private fun containsAscii(
        haystack: ByteArray,
        needle: String,
    ): Boolean {
        val target = needle.toByteArray(Charsets.US_ASCII)
        val last = haystack.size - target.size
        for (start in 0..last) {
            if (matchesAt(haystack, target, start)) return true
        }
        return false
    }

    private fun matchesAt(
        haystack: ByteArray,
        target: ByteArray,
        start: Int,
    ): Boolean {
        for (i in target.indices) {
            if (haystack[start + i] != target[i]) return false
        }
        return true
    }

    private companion object {
        const val WAV_HEADER_SIZE = 44
    }
}
