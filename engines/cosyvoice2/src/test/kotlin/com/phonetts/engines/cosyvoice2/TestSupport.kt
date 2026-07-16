package com.phonetts.engines.cosyvoice2

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.runtime.SpeechTokenRequest
import com.phonetts.core.testing.FakePhonemizer
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession
import com.phonetts.core.testing.FakeSpeechTokenRuntime
import com.phonetts.core.testing.FakeSpeechTokenSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Shared scaffolding for this module's tests only. CosyVoice2 is unique in the registry: it wires
// TWO runtimes (the non-ONNX SpeechTokenRuntime LLM + the ONNX flow/hift backend), so it builds its
// own EngineContext here rather than reuse engines.common's single-runtime helper.

/** A bundle that [CosyVoice2Engine.inspect] confidently recognizes: gguf LLM + flow + hift + signed config. */
internal fun validBundle(
    id: String = "cosyvoice2-bundle",
    config: String = "model_type: cosyvoice2\n",
): ModelBundle =
    ModelBundle(
        id = id,
        fileNames = setOf("llm.gguf", "flow.onnx", "hift.onnx", "voices.bin", "cosyvoice2.yaml"),
        sideFiles = mapOf("cosyvoice2.yaml" to config),
        rootPath = "/models/$id",
    )

/** An [EngineContext] with no runtime registered — enough to exercise inspect()/forcedMatch(). */
internal fun emptyContext(): EngineContext = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

/**
 * An [EngineContext] wired with the two runtimes CosyVoice2 uses: the LLM-style
 * [FakeSpeechTokenRuntime] under [CosyVoice2Engine.LLM_RUNTIME_ID] and the ONNX [FakeRuntime]
 * under [CosyVoice2Engine.ONNX_RUNTIME_ID], which routes flow/hift sessions by file suffix.
 */
internal fun contextWith(
    llm: FakeSpeechTokenRuntime,
    flowSession: FakeSession,
    hiftSession: FakeSession,
): EngineContext {
    val onnx =
        FakeRuntime(
            id = CosyVoice2Engine.ONNX_RUNTIME_ID,
            sessionFactory = { path ->
                when {
                    path.endsWith(CosyVoice2Engine.FLOW_FILE) -> flowSession
                    path.endsWith(CosyVoice2Engine.HIFT_FILE) -> hiftSession
                    else -> error("unexpected onnx model path in test: $path")
                }
            },
        )
    val runtimes =
        RuntimeRegistry().apply {
            register(llm)
            register(onnx)
        }
    return EngineContext(runtimes = runtimes, phonemizer = FakePhonemizer())
}

/** A [FakeSpeechTokenRuntime] under CosyVoice2's LLM id, its session decoding via [tokensFor]. */
internal fun llmRuntime(
    available: Boolean = true,
    tokensFor: (SpeechTokenRequest) -> LongArray = { LongArray(DEFAULT_TOKENS_PER_SENTENCE) { i -> i.toLong() } },
): FakeSpeechTokenRuntime =
    FakeSpeechTokenRuntime(
        id = CosyVoice2Engine.LLM_RUNTIME_ID,
        available = available,
        sessionFactory = { FakeSpeechTokenSession(tokensFor = tokensFor) },
    )

/** A [CosyVoice2Engine] whose voices.bin reads back a valid synthetic baked voice (parse path). */
internal fun engineWithBakedVoice(context: EngineContext): CosyVoice2Engine =
    CosyVoice2Engine(context, fileReader = { bakedVoiceBytes() })

/** Encodes a minimal, well-formed voices.bin (see [CosyVoice2SpeakerPrompt] for the layout). */
internal fun bakedVoiceBytes(
    embeddingDim: Int = 4,
    promptTokenCount: Int = 2,
    promptFeatFrames: Int = 3,
): ByteArray {
    val featCount = promptFeatFrames * CosyVoice2Graphs.MEL_DIM
    val size = (3 + embeddingDim) * Int.SIZE_BYTES + promptTokenCount * Int.SIZE_BYTES + featCount * Float.SIZE_BYTES
    val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(embeddingDim).putInt(promptTokenCount).putInt(promptFeatFrames)
    repeat(embeddingDim) { buffer.putFloat(0.2f) }
    repeat(promptTokenCount) { buffer.putInt(it) }
    repeat(featCount) { buffer.putFloat(0.1f) }
    return buffer.array()
}

internal const val DEFAULT_TOKENS_PER_SENTENCE = 5
