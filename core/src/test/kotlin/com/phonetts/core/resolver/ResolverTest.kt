package com.phonetts.core.resolver

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.testing.FakeEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverTest {
    private val knownBundle = ModelBundle(id = "known-bundle", fileNames = setOf("model.onnx"))
    private val unknownBundle = ModelBundle(id = "unknown-bundle", fileNames = setOf("mystery.bin"))

    @Test
    fun `a saved override for a removed engine is ignored and the bundle is re-detected`() {
        // Simulate an engine that was unregistered after its decision was persisted.
        val store = InMemoryOverrideStore().apply { put(knownBundle.id, "engine-that-was-removed") }
        val claimingEngine = FakeEngine(id = "engine-a", claims = { it.id == knownBundle.id })
        val resolver =
            Resolver(
                engines = listOf(claimingEngine),
                overrideStore = store,
                userPicksEngine = { error("should re-detect, not ask the user") },
            )

        val descriptor = resolver.resolve(knownBundle)

        // Did not crash on the stale override; fell through to detection.
        assertEquals("engine-a", descriptor.engineId)
    }

    @Test
    fun `resolve returns a complete descriptor for a known auto-detected bundle`() {
        val claimingEngine = FakeEngine(id = "engine-a", claims = { it.id == knownBundle.id })
        val otherEngine = FakeEngine(id = "engine-b")
        val resolver =
            Resolver(
                engines = listOf(claimingEngine, otherEngine),
                overrideStore = InMemoryOverrideStore(),
                userPicksEngine = { error("should not be asked to pick for a known bundle") },
            )

        val descriptor = resolver.resolve(knownBundle)

        assertEquals("engine-a", descriptor.engineId)
        assertEquals(knownBundle.id, descriptor.modelId)
        assertTrue(descriptor.sampleRate > 0)
        assertTrue(descriptor.voices.isNotEmpty())
        assertTrue(descriptor.voices.any { it.id == descriptor.defaultVoiceId })
        assertTrue(descriptor.defaultSpeed in descriptor.speedRange)
    }

    @Test
    fun `resolve falls back to userPicksEngine when no engine claims the bundle`() {
        val engineA = FakeEngine(id = "engine-a")
        val engineB = FakeEngine(id = "engine-b")
        var userWasAsked = false
        val resolver =
            Resolver(
                engines = listOf(engineA, engineB),
                overrideStore = InMemoryOverrideStore(),
                userPicksEngine = { bundle ->
                    userWasAsked = true
                    assertEquals(unknownBundle.id, bundle.id)
                    "engine-b"
                },
            )

        val descriptor = resolver.resolve(unknownBundle)

        assertTrue(userWasAsked)
        assertEquals("engine-b", descriptor.engineId)
    }

    @Test
    fun `second resolve for the same bundle reads the saved override and skips re-detection`() {
        val claimingEngine = FakeEngine(id = "engine-a", claims = { it.id == knownBundle.id })
        val otherEngine = FakeEngine(id = "engine-b")
        val resolver =
            Resolver(
                engines = listOf(claimingEngine, otherEngine),
                overrideStore = InMemoryOverrideStore(),
                userPicksEngine = { error("should not be asked to pick for a known bundle") },
            )

        resolver.resolve(knownBundle)
        val inspectCountAfterFirstResolve = claimingEngine.inspectCount
        val descriptor = resolver.resolve(knownBundle)

        assertTrue(inspectCountAfterFirstResolve > 0, "sanity check: first resolve must have inspected")
        assertEquals(inspectCountAfterFirstResolve, claimingEngine.inspectCount)
        assertEquals("engine-a", descriptor.engineId)
    }
}
