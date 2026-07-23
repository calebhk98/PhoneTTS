package com.phonetts.core.audio.export

import com.phonetts.core.audio.transform.TransformChain
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

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
        val writer = openWriter(sampleRate, out)
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
     */
    protected abstract fun openWriter(
        sampleRate: Int,
        out: OutputStream,
    ): SegmentWriter
}
