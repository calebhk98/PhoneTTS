package com.phonetts.core.audio.transform

// Composes the enabled transforms into one streaming pipeline for bounded-memory export. A segment
// pushed in flows through every stage in order and out to [sink] (the encoder's writer); each
// stage releases settled output as it goes, so at most one stage's bounded working set is resident
// at a time. This is the streaming sibling of [TransformChain.apply] — same order, same result,
// but never materializing the whole utterance.
class TransformPipeline internal constructor(
    private val stages: List<TransformStage>,
    private val sink: (FloatArray) -> Unit,
) {
    /** Push one freshly-generated segment through the whole chain. */
    fun push(segment: FloatArray) = feed(0, segment)

    /** Drain each stage in order; a stage's flushed output flows through all stages after it. */
    fun finish() {
        for (index in stages.indices) {
            stages[index].finish { produced -> feed(index + 1, produced) }
        }
    }

    // Feed [segment] into stage [index]; past the last stage it is settled, so hand it to the sink.
    private fun feed(
        index: Int,
        segment: FloatArray,
    ) {
        if (index >= stages.size) {
            sink(segment)
            return
        }
        stages[index].push(segment) { produced -> feed(index + 1, produced) }
    }
}

// Fallback stage for a transform that has no streaming form: it buffers every segment and applies
// the batch transform at the end. This reintroduces full-buffer memory use for THAT transform only
// (SilenceTrim today), and only when the user has enabled it — the common no-transform and
// streaming-transform paths stay bounded.
internal class BufferingStage(
    private val transform: AudioTransform,
    private val sampleRate: Int,
) : TransformStage {
    private val buffered = ArrayList<FloatArray>()

    override fun push(
        segment: FloatArray,
        emit: (FloatArray) -> Unit,
    ) {
        buffered.add(segment)
    }

    override fun finish(emit: (FloatArray) -> Unit) {
        transform.apply(buffered, sampleRate).forEach(emit)
    }
}
