package com.phonetts.core.audio.export

import com.phonetts.core.audio.transform.TransformChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

// Base class for every file-export consumer. It is the file-side sibling of StreamingConsumer:
// both drain the ONE `synthesize()` flow, so a new export format is a new *consumer*, never a new
// synthesis path (spec §6.1). This parent owns the shared work - draining the flow INCREMENTALLY
// through the (optional, non-destructive) transform pipeline - so each concrete format (WAV here;
// AAC/Opus in :app) only implements the raw byte encoding, with zero duplicated drain/transform
// code.
//
// Bounded memory (issue #33): the flow is streamed one segment at a time into a [SegmentWriter];
// the base never buffers the whole utterance as a List<FloatArray>. Streaming transforms release
// settled segments as they go; only a full-buffer transform the user has explicitly enabled
// (SilenceTrim) still buffers, and only itself.
abstract class AudioEncoder {
    /** The container this encoder produces. The export UI reads its extension/MIME from here. */
    abstract val format: ExportFormat

    /**
     * Drain [flow] incrementally, running the enabled [transforms] as a streaming pipeline and
     * committing each settled segment to a [SegmentWriter] as it appears. Transforms run here so
     * every format gets identical, non-destructive post-processing for free.
     */
    suspend fun encode(
        flow: Flow<FloatArray>,
        sampleRate: Int,
        out: OutputStream,
        transforms: TransformChain? = null,
    ) {
        // Captured HERE (a suspend context) and threaded down to [openWriter] so a [SegmentWriter]
        // whose close() runs a long, otherwise-uninterruptible native pass (MediaCodec's AAC/Opus
        // encoders in :app) can still notice this coroutine being cancelled - `close()` itself is a
        // plain, non-suspend function with no way to ask the coroutine machinery on its own (issue:
        // export cannot be cancelled). A writer that never runs anything long (WavEncoder) simply
        // ignores it.
        val context = coroutineContext
        val isCancelled = { !context.isActive }
        val writer = openWriter(sampleRate, out, isCancelled)
        try {
            val chain = transforms ?: TransformChain(emptyList())
            val pipeline = chain.pipeline(sampleRate) { segment -> writer.write(segment) }
            flow.collect { segment -> pipeline.push(segment) }
            pipeline.finish()
        } finally {
            writer.close()
        }
    }

    /**
     * Open a bounded-memory [SegmentWriter] for one export to [out] at [sampleRate]. Each concrete
     * format implements the raw byte encoding here; the base owns the drain + transform streaming.
     * [isCancelled] reflects the [encode] caller's own coroutine cancellation - a platform-specific
     * writer whose `close()` may run a long, otherwise-uninterruptible native pass (a MediaCodec
     * AAC/Opus encode, say - :core has no such writer itself) should poll it and bail out; one that
     * doesn't need to (like the plain WAV writer below) can ignore it.
     */
    protected abstract fun openWriter(
        sampleRate: Int,
        out: OutputStream,
        isCancelled: () -> Boolean = { false },
    ): SegmentWriter
}
