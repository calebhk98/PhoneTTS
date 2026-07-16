package com.phonetts.engines.cosyvoice2

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.testing.FakeNativeTtsRuntime
import com.phonetts.core.testing.FakeNativeTtsSession
import com.phonetts.core.testing.FakePhonemizer

// Shared scaffolding for this module's tests only. CosyVoice is unique in the registry: its runtime
// is the non-ONNX NativeTtsRuntime (a full text→audio ggml pipeline), so it builds its own
// EngineContext here rather than reuse engines.common's ONNX single-runtime helper.

/**
 * A bundle that [CosyVoice2Engine.inspect] confidently recognizes: all four GGUF stages of the
 * native CosyVoice3 stack (LLM + flow + HiFT + voices), matched by stage prefix.
 */
internal fun validBundle(id: String = "cosyvoice3-bundle"): ModelBundle =
    ModelBundle(
        id = id,
        fileNames =
            setOf(
                "cosyvoice3-llm-q4_k.gguf",
                "cosyvoice3-flow-q8_0.gguf",
                "cosyvoice3-hift-f16.gguf",
                "cosyvoice3-voices.gguf",
            ),
        rootPath = "/models/$id",
    )

/** An [EngineContext] with no runtime registered — enough to exercise inspect()/forcedMatch(). */
internal fun emptyContext(): EngineContext = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

/**
 * An [EngineContext] wired with the one runtime CosyVoice uses: the non-ONNX
 * [FakeNativeTtsRuntime] under [CosyVoice2Engine.NATIVE_RUNTIME_ID].
 */
internal fun contextWith(runtime: FakeNativeTtsRuntime): EngineContext =
    EngineContext(
        runtimes = RuntimeRegistry().apply { register(runtime) },
        phonemizer = FakePhonemizer(),
    )

/**
 * A [FakeNativeTtsRuntime] under CosyVoice's native id, its session voicing each request via
 * [audioFor] and reporting [voiceNames] as the model's baked voices.
 */
internal fun cosyRuntime(
    available: Boolean = true,
    voiceNames: List<String> = listOf("zero_shot", "fleurs-en"),
    audioFor: (NativeTtsRequest) -> FloatArray = { FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { 0.1f } },
): FakeNativeTtsRuntime =
    FakeNativeTtsRuntime(
        id = CosyVoice2Engine.NATIVE_RUNTIME_ID,
        available = available,
        sessionFactory = { FakeNativeTtsSession(voiceNames = voiceNames, audioFor = audioFor) },
    )

internal const val DEFAULT_SAMPLES_PER_SENTENCE = 1280
