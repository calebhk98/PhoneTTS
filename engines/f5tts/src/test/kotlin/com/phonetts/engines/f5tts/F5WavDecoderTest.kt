package com.phonetts.engines.f5tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `F5WavDecoder` reads the reference clips this engine bundles as `<voice>.reference.wav`
 * (README-io.md) — this pins its normalization convention (`[-1, 1]` floats, matching
 * `core/audio/Pcm16.kt`) and the RIFF/WAVE chunk parsing against bytes this module itself builds.
 */
class F5WavDecoderTest {
    @Test
    fun `decodes mono 16-bit PCM samples normalized to -1 to 1`() {
        val samples = shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE, -1)
        val bytes = buildWavBytes(samples, sampleRate = 24_000, channels = 1)

        val decoded = F5WavDecoder.decode(bytes)

        assertEquals(24_000, decoded.sampleRate)
        assertEquals(1, decoded.channels)
        assertEquals(4, decoded.samples.size)
        assertEquals(0f, decoded.samples[0], 1e-6f)
        assertEquals(1f, decoded.samples[1], 1e-3f)
        assertEquals(-1f, decoded.samples[2], 1e-3f)
    }

    @Test
    fun `downmixes stereo by averaging channels`() {
        // One stereo frame: left = MAX (1.0), right = 0 -> average halfway to 1.
        val interleaved = shortArrayOf(Short.MAX_VALUE, 0)
        val bytes = buildWavBytes(interleaved, sampleRate = 24_000, channels = 2)

        val decoded = F5WavDecoder.decode(bytes)

        assertEquals(2, decoded.channels)
        assertEquals(1, decoded.samples.size)
        assertEquals(0.5f, decoded.samples[0], 1e-2f)
    }

    @Test
    fun `reads a non-default sample rate from the fmt chunk`() {
        val bytes = buildWavBytes(shortArrayOf(0, 0), sampleRate = 22_050, channels = 1)

        val decoded = F5WavDecoder.decode(bytes)

        assertEquals(22_050, decoded.sampleRate)
    }

    @Test
    fun `rejects data too short to contain a wav header`() {
        assertFailsWith<IllegalArgumentException> { F5WavDecoder.decode(ByteArray(4)) }
    }

    @Test
    fun `rejects data missing the RIFF header`() {
        val bytes = buildWavBytes(shortArrayOf(0), 24_000, 1)
        bytes[0] = 'X'.code.toByte()

        assertFailsWith<IllegalArgumentException> { F5WavDecoder.decode(bytes) }
    }

    @Test
    fun `rejects data with no data chunk`() {
        // A well-formed RIFF/WAVE/fmt header but truncated right after it, before any data chunk.
        val fullBytes = buildWavBytes(shortArrayOf(1, 2, 3), 24_000, 1)
        val truncated = fullBytes.copyOfRange(0, fullBytes.size - 3 * 2 - 8)

        assertFailsWith<IllegalStateException> { F5WavDecoder.decode(truncated) }
    }
}
