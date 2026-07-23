package com.phonetts.core.audio.transform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// A single second-order (biquad) IIR filter section - the shared primitive behind the EQ-flavored
// transforms (bass cut, presence boost, de-ess). Coefficients follow the standard RBJ audio-EQ
// cookbook and are normalized by a0 up front, so [process] is just the direct-form-I difference
// equation. State (the two previous input/output samples) is LOCAL to a [process] call and reset
// each time it is invoked, which is exactly the per-segment, no-cross-segment-state contract the
// transforms rely on: filtering each sentence chunk independently, never carrying a tail across a
// boundary.
class Biquad private constructor(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    /** Filter [input] into a fresh array; the input is never mutated. */
    fun process(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
        for (i in input.indices) {
            val x0 = input[i]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return out
    }

    companion object {
        // Second-order high-pass: passes highs, attenuates everything below [freq].
        fun highPass(
            sampleRate: Int,
            freq: Float,
            q: Float,
        ): Biquad {
            val (cosW0, alpha) = intermediates(sampleRate, freq, q)
            val a0 = 1f + alpha
            val b0 = (1f + cosW0) / 2f
            return Biquad(b0 / a0, -(1f + cosW0) / a0, b0 / a0, -(TWO * cosW0) / a0, (1f - alpha) / a0)
        }

        // Peaking EQ: boosts (positive [gainDb]) or cuts a band centered on [freq].
        fun peaking(
            sampleRate: Int,
            freq: Float,
            q: Float,
            gainDb: Float,
        ): Biquad {
            val (cosW0, alpha) = intermediates(sampleRate, freq, q)
            val a = DB_BASE.pow(gainDb / SHELF_DB_DIVISOR)
            val a0 = 1f + alpha / a
            return Biquad(
                (1f + alpha * a) / a0,
                -(TWO * cosW0) / a0,
                (1f - alpha * a) / a0,
                -(TWO * cosW0) / a0,
                (1f - alpha / a) / a0,
            )
        }

        // High-shelf: lifts or (with negative [gainDb]) tames everything above the [freq] corner -
        // the de-esser uses a gentle negative shelf to soften sibilant highs.
        fun highShelf(
            sampleRate: Int,
            freq: Float,
            q: Float,
            gainDb: Float,
        ): Biquad {
            val (cosW0, alpha) = intermediates(sampleRate, freq, q)
            val a = DB_BASE.pow(gainDb / SHELF_DB_DIVISOR)
            val twoSqrtAAlpha = TWO * sqrt(a) * alpha
            val a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
            return Biquad(
                a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha) / a0,
                -(TWO * a) * ((a - 1f) + (a + 1f) * cosW0) / a0,
                a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha) / a0,
                2f * ((a - 1f) - (a + 1f) * cosW0) / a0,
                ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha) / a0,
            )
        }

        // Shared w0/alpha computation; freq is clamped below Nyquist so coefficients stay finite.
        private fun intermediates(
            sampleRate: Int,
            freq: Float,
            q: Float,
        ): Pair<Float, Float> {
            val nyquist = sampleRate / 2f
            val safeFreq = freq.coerceIn(1f, nyquist * NYQUIST_MARGIN)
            val w0 = 2.0 * PI * safeFreq / sampleRate
            val cosW0 = cos(w0).toFloat()
            val alpha = (sin(w0) / (2.0 * q)).toFloat()
            return cosW0 to alpha
        }

        private const val TWO = 2f
        private const val DB_BASE = 10f // dB → linear amplitude uses 10^(dB/40)
        private const val SHELF_DB_DIVISOR = 40f
        private const val NYQUIST_MARGIN = 0.99f // keep design freq just under Nyquist
    }
}
