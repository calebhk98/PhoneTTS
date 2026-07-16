package com.phonetts.engines.cosyvoice2

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A single pre-baked speaker voice — the on-device answer to "no reference wav in v1"
 * (docs/research/cosyvoice2-mobile.md §Q5). A baked voice is the three things the flow-matching
 * decoder needs to condition on: the 192-D CAMPPlus [embedding], the [promptTokens] (speech
 * tokens of the prompt clip) and the [promptFeat] mel of the prompt clip
 * (`promptFeatFrames` frames of [CosyVoice2Graphs.MEL_DIM] bins). Upstream ships these as an
 * 8-voice `cosyvoice3-voices.gguf`; PhoneTTS bundles its own compact binary equivalent (see
 * [CosyVoice2SpeakerPrompt.parse] for the layout), produced by an offline baking script — a
 * documented native TODO, not yet committed (weights are never committed, CLAUDE.md).
 */
internal class SpeakerPrompt(
    val embedding: FloatArray,
    val promptTokens: LongArray,
    val promptFeat: FloatArray,
    val promptFeatFrames: Int,
)

/**
 * Reader for PhoneTTS's own little-endian baked-voice file (`voices.bin`). Deliberately simple and
 * self-describing so it fails closed on a malformed file rather than reshape-guessing (spec §9.1),
 * mirroring [com.phonetts.engines.kokoro] voice decoding. Layout, all little-endian:
 *
 *   int32 embeddingDim
 *   int32 promptTokenCount
 *   int32 promptFeatFrames
 *   embeddingDim   x float32   -- CAMPPlus speaker embedding
 *   promptTokenCount x int32   -- prompt speech tokens (widened to Long for the flow graph)
 *   promptFeatFrames x MEL_DIM x float32  -- prompt mel feature, row-major [frames, MEL_DIM]
 *
 * NOTE: this is the app's baking format, NOT an upstream artifact; the exact byte layout is fixed
 * here so a future baking script and this reader agree. It is unverified on real weights.
 */
internal object CosyVoice2SpeakerPrompt {
    private const val HEADER_INTS = 3

    /** Decode [bytes] into a [SpeakerPrompt], or null if the header and body sizes disagree. */
    fun parse(bytes: ByteArray): SpeakerPrompt? {
        if (bytes.size < HEADER_INTS * Int.SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val embeddingDim = buffer.int
        val promptTokenCount = buffer.int
        val promptFeatFrames = buffer.int
        if (embeddingDim < 0 || promptTokenCount < 0 || promptFeatFrames < 0) return null

        val featCount = promptFeatFrames * CosyVoice2Graphs.MEL_DIM
        if (buffer.remaining() != expectedBodyBytes(embeddingDim, promptTokenCount, featCount)) return null

        val embedding = FloatArray(embeddingDim) { buffer.float }
        val promptTokens = LongArray(promptTokenCount) { buffer.int.toLong() }
        val promptFeat = FloatArray(featCount) { buffer.float }
        return SpeakerPrompt(embedding, promptTokens, promptFeat, promptFeatFrames)
    }

    private fun expectedBodyBytes(
        embeddingDim: Int,
        promptTokenCount: Int,
        featCount: Int,
    ): Int = embeddingDim * Float.SIZE_BYTES + promptTokenCount * Int.SIZE_BYTES + featCount * Float.SIZE_BYTES

    /**
     * A neutral, correctly-shaped stand-in used for a forced/sideloaded bundle with no baked
     * `voices.bin`. It keeps synthesize()'s flow/vocoder plumbing exercised end-to-end; real voice
     * quality for such bundles is out of scope for this seam (spec TDD note: "test the plumbing,
     * not the audio").
     */
    fun fallback(): SpeakerPrompt =
        SpeakerPrompt(
            embedding = FloatArray(FALLBACK_EMBEDDING_DIM),
            promptTokens = longArrayOf(0L),
            promptFeat = FloatArray(CosyVoice2Graphs.MEL_DIM),
            promptFeatFrames = 1,
        )

    // CAMPPlus speaker embeddings are 192-D (docs/research/cosyvoice2-mobile.md §Q2). Used only to
    // shape the fallback prompt; a real baked voice.bin carries its own embeddingDim in the header.
    private const val FALLBACK_EMBEDDING_DIM = 192
}
