package com.phonetts.engines.common.testing

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.EngineRegistry
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession
import com.phonetts.core.text.Phonemizer
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Shared test scaffolding for every engine module's test suite (spec §9 seam tests). The first
// dedup pass extracted shared MAIN plumbing into :engines:common; this testFixtures source set is
// its TEST-side counterpart - every one of the five engine test suites independently reimplemented
// "build an EngineContext wired to a fake ONNX runtime/session", the inspect() fail-closed assertion
// (spec §9.1), and the two ServiceLoader-discovery invariants every engine's own ProviderTest
// re-proves (spec §5.4). All parameterized by caller-supplied engines/ids/sessions - this file
// names no model, exactly like the rest of :engines:common.

/** The runtime id every engine except CosyVoice2's LLM-style backend registers under. */
const val ONNX_RUNTIME_ID = "onnx"

/** An [EngineContext] with [runtime] registered (if non-null) and a default identity phonemizer. */
fun engineContext(
    runtime: FakeRuntime? = null,
    phonemizer: Phonemizer = FakePhonemizer(),
): EngineContext {
    val runtimes = RuntimeRegistry().apply { runtime?.let(::register) }
    return EngineContext(runtimes = runtimes, phonemizer = phonemizer)
}

/** A [FakeRuntime] registered under [ONNX_RUNTIME_ID] that always hands back [session]. */
fun onnxRuntime(session: FakeSession): FakeRuntime = FakeRuntime(id = ONNX_RUNTIME_ID, sessionFactory = { session })

/** A [FakeRuntime] registered under [ONNX_RUNTIME_ID] that picks a session per model path. */
fun onnxRuntime(sessionFor: (String) -> FakeSession): FakeRuntime =
    FakeRuntime(id = ONNX_RUNTIME_ID, sessionFactory = sessionFor)

/** [engineContext] pre-wired with a single-session ONNX [FakeRuntime] - the common one-graph case. */
fun onnxEngineContext(
    session: FakeSession = FakeSession(),
    phonemizer: Phonemizer = FakePhonemizer(),
): EngineContext = engineContext(onnxRuntime(session), phonemizer)

/** inspect() must fail closed (spec §9.1): a named, non-weakened `assertNull` for that seam. */
fun assertInspectRejects(
    engine: VoiceEngine,
    bundle: ModelBundle,
) {
    assertNull(engine.inspect(bundle), "expected inspect() to refuse bundle '${bundle.id}', but it claimed it")
}

/** Every [EngineProvider]'s core invariant: the engine it creates reports the provider's own id. */
fun assertCreatesMatchingEngine(
    provider: EngineProvider,
    context: EngineContext = engineContext(),
): VoiceEngine {
    val engine = provider.create(context)
    assertEquals(provider.engineId, engine.id, "provider.create(...).id must equal provider.engineId")
    return engine
}

/**
 * Proves the discovery seam (spec §5.4): exactly one provider with [engineId] is found via
 * `ServiceLoader` on the calling module's classpath, and it is an instance of [P].
 */
inline fun <reified P : EngineProvider> assertSingleDiscoveredProvider(engineId: String): P {
    val providers = EngineLoader.discoverProviders()
    val match = providers.singleOrNull { it.engineId == engineId }
    val foundIds = providers.map { it.engineId }
    val message = "expected exactly one discovered provider with engineId '$engineId', found: $foundIds"
    return assertIs<P>(assertNotNull(match, message))
}

/** [EngineLoader.seed] must register a working engine for [engineId] into a fresh [EngineRegistry]. */
fun assertSeedsEngineIntoRegistry(
    engineId: String,
    context: EngineContext = engineContext(),
): VoiceEngine {
    val registry = EngineRegistry()
    EngineLoader.seed(registry, context)
    return assertNotNull(registry.get(engineId), "expected EngineLoader.seed to register engine '$engineId'")
}
