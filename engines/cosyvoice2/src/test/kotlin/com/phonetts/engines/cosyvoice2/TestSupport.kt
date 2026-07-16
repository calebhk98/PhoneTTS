package com.phonetts.engines.cosyvoice2

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession

// Shared scaffolding for this module's tests only (not shipped in main, not testFixtures —
// nothing outside engines/cosyvoice2 needs a CosyVoice2-shaped bundle or runtime).

/** A bundle that [CosyVoice2Engine.inspect] confidently recognizes: all three weight files + a signed config. */
internal fun validBundle(
    id: String = "cosyvoice2-bundle",
    config: String = "model_type: cosyvoice2\n",
): ModelBundle =
    ModelBundle(
        id = id,
        fileNames = setOf("llm.onnx", "flow.onnx", "hift.onnx", "cosyvoice2.yaml"),
        sideFiles = mapOf("cosyvoice2.yaml" to config),
        rootPath = "/models/$id",
    )

/** An [EngineContext] with no runtime registered — enough to exercise inspect()/forcedMatch(). */
internal fun emptyContext(): EngineContext = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

/** An [EngineContext] whose runtime registry has [runtime] registered under its own id. */
internal fun contextWithRuntime(runtime: FakeRuntime): EngineContext =
    EngineContext(runtimes = RuntimeRegistry().apply { register(runtime) }, phonemizer = FakePhonemizer())

/**
 * A [FakeRuntime] registered under [CosyVoice2Engine.RUNTIME_ID] that hands back the given
 * pre-scripted session for whichever of the three component paths [CosyVoice2Engine.load] asks
 * to create, based on the file-name suffix the engine builds in [CosyVoice2Engine] asset paths.
 */
internal fun fakeRuntimeFor(
    llmSession: FakeSession,
    flowSession: FakeSession,
    hiftSession: FakeSession,
): FakeRuntime =
    FakeRuntime(
        id = CosyVoice2Engine.RUNTIME_ID,
        sessionFactory = { path ->
            when {
                path.endsWith("llm.onnx") -> llmSession
                path.endsWith("flow.onnx") -> flowSession
                path.endsWith("hift.onnx") -> hiftSession
                else -> error("unexpected model path in test: $path")
            }
        },
    )
