package com.phonetts.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

// Single place that turns generated float samples ([-1, 1]) into signed 16-bit PCM. Every file
// encoder (WAV today, MP3/Opus/AAC in :app) and any int16 audio sink share THIS conversion so the
// quantization rule lives once, not copied per format (mirrors the SSOT discipline for model facts).
private const val BYTES_PER_SAMPLE = 2

/** Float PCM ([-1f, 1f]) to signed little-endian 16-bit PCM. The one conversion used everywhere. */
object Pcm16 {
    /**
     * Quantize a single sample, clamping out-of-range values instead of wrapping. A NaN sample
     * (e.g. a runtime producing a stray non-finite value) is treated as silence rather than
     * propagated - `Float.roundToInt()` throws `IllegalArgumentException` on NaN, so this must be
     * checked before scaling/rounding, not left to `coerceIn` (which only guards the finite range).
     */
    fun toShort(sample: Float): Short {
        if (sample.isNaN()) return 0
        val scaled = (sample * Short.MAX_VALUE).roundToInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** Encode a whole chunk to little-endian int16 bytes. */
    fun encode(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) buffer.putShort(toShort(sample))
        return buffer.array()
    }
}
