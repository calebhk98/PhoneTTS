package com.phonetts.core.audio.transform

import java.io.File
import kotlin.math.abs

// Peak-normalizes the whole utterance to a target headroom: finds the loudest sample across all
// segments and scales everything by one gain so the peak lands at [targetPeak]. One shared gain
// keeps relative dynamics intact (this is not a compressor). Silent audio is left untouched.
//
// A single global gain fundamentally needs to see every sample before it can scale any of them, so
// there is no zero-memory streaming form. The bounded-memory export path therefore runs it as a
// TWO-PASS stage backed by a [SegmentSpill] scratch file: pass one scans the peak while spilling
// raw floats to DISK (heap stays at one segment); pass two reads them back and applies the one gain
// as it emits. Result is bit-identical to the batch [apply] — same peak, same single gain — but the
// utterance never sits fully in RAM. The tradeoff vs. a running/adaptive "streaming gain" is a
// scratch-file round-trip; we take that so relative dynamics and the exact target peak are kept.
private const val DEFAULT_TARGET_PEAK = 0.95f

class LoudnessNormalize(
    private val targetPeak: Float = DEFAULT_TARGET_PEAK,
    // Scratch dir for the two-pass spill; null = JVM default temp (app-private cache on Android).
    private val scratchDir: File? = null,
) : AudioTransform, IncrementalTransform {
    override val id: String = ID
    override val displayName: String = "Normalize volume"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        val peak = segments.maxOfOrNull { segment -> segment.maxOfOrNull { abs(it) } ?: 0f } ?: 0f
        if (peak == 0f) return segments
        val gain = targetPeak / peak
        if (gain == 1f) return segments
        return segments.map { segment -> scaleBy(segment, gain) }
    }

    override fun openStage(sampleRate: Int): TransformStage = LoudnessStage(targetPeak, scratchDir)

    companion object {
        const val ID = "loudness-normalize"
    }
}

// Two-pass streaming normalization. push() tracks the running peak and spills each segment; finish()
// computes the one gain and re-emits the spilled segments scaled by it. Bounded heap: a peak scalar
// plus one segment at a time.
private class LoudnessStage(
    private val targetPeak: Float,
    scratchDir: File?,
) : TransformStage {
    private val spill = SegmentSpill(scratchDir)
    private var peak = 0f

    override fun push(
        segment: FloatArray,
        emit: (FloatArray) -> Unit,
    ) {
        for (sample in segment) {
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
        }
        spill.append(segment)
    }

    override fun finish(emit: (FloatArray) -> Unit) {
        spill.use {
            val gain = if (peak == 0f) 1f else targetPeak / peak
            it.forEachSegment { segment -> emit(if (gain == 1f) segment else scaleBy(segment, gain)) }
        }
    }
}

private fun scaleBy(
    segment: FloatArray,
    gain: Float,
): FloatArray = FloatArray(segment.size) { segment[it] * gain }
