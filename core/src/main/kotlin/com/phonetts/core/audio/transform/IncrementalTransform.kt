package com.phonetts.core.audio.transform

// Streaming capability that lets a transform run inside the bounded-memory export path: instead of
// being handed the whole List<FloatArray> at once (batch `AudioTransform.apply`), it processes the
// utterance segment by segment and EMITS the parts that are settled — safe to write and forget.
// A transform that cannot work this way (it needs the full buffer, e.g. SilenceTrim's trailing-
// silence scan) simply does NOT implement this, and the export path falls back to buffering it.
interface IncrementalTransform {
    /** Open a fresh streaming stage for one export run at [sampleRate]. */
    fun openStage(sampleRate: Int): TransformStage
}

// One transform's live streaming state for a single export. The [emit] callback hands a produced
// segment downstream (to the next stage, or to the encoder's writer) the moment it is settled, so
// nothing accumulates on the heap beyond each stage's own bounded working set.
interface TransformStage {
    /** Consume one input segment, emitting any now-settled output via [emit]. */
    fun push(
        segment: FloatArray,
        emit: (FloatArray) -> Unit,
    )

    /** Flush whatever the stage was holding back once the input is exhausted. */
    fun finish(emit: (FloatArray) -> Unit)
}
