package com.phonetts.core.audio.export

import com.phonetts.core.audio.transform.TransformChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import java.io.OutputStream

// Base class for every file-export consumer. It is the file-side sibling of StreamingConsumer:
// both drain the ONE `synthesize()` flow, so a new export format is a new *consumer*, never a new
// synthesis path (spec §6.1). This parent owns the shared work — draining the flow and applying
// the (optional, non-destructive) transform chain — so each concrete format (WAV here; MP3/Opus/
// AAC in :app) only implements the raw byte encoding, with zero duplicated drain/transform code.
abstract class AudioEncoder {
    /** The container this encoder produces. The export UI reads its extension/MIME from here. */
    abstract val format: ExportFormat

    /**
     * Drain [flow], apply the enabled transforms in [transforms] (if any) to the buffered audio,
     * then hand the finished segments to [writeEncoded]. Transforms run here so every format gets
     * identical, non-destructive post-processing for free.
     */
    suspend fun encode(
        flow: Flow<FloatArray>,
        sampleRate: Int,
        out: OutputStream,
        transforms: TransformChain? = null,
    ) {
        val segments = flow.toList()
        val processed = transforms?.apply(segments, sampleRate) ?: segments
        writeEncoded(processed, sampleRate, out)
    }

    /** Encode already-buffered, already-transformed [segments] into [out] in this format. */
    protected abstract suspend fun writeEncoded(
        segments: List<FloatArray>,
        sampleRate: Int,
        out: OutputStream,
    )
}
