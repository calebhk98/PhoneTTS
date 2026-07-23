package com.phonetts.core.resolver

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.testing.FakeEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // Bug #1: the manual "pick an engine" fallback must actually be reachable and working, not just
    // described. selectableEngines() is what a picker UI reads to offer choices.
    @Test
    fun `selectableEngines exposes every registered engine's id and display name`() {
        val engineA = FakeEngine(id = "engine-a", displayName = "Engine A")
        val engineB = FakeEngine(id = "engine-b", displayName = "Engine B")
        val resolver =
            Resolver(
                engines = listOf(engineA, engineB),
                overrideStore = InMemoryOverrideStore(),
                userPicksEngine = { error("not exercised by this test") },
            )

        val selectable = resolver.selectableEngines()

        assertEquals(
            listOf(SelectableEngine("engine-a", "Engine A"), SelectableEngine("engine-b", "Engine B")),
            selectable,
        )
    }

    @Test
    fun `resolveWithChosenEngine uses forcedMatch and persists the choice for future resolves`() {
        val chosenEngine = FakeEngine(id = "engine-b")
        val otherEngine = FakeEngine(id = "engine-a")
        val store = InMemoryOverrideStore()
        val resolver =
            Resolver(
                engines = listOf(otherEngine, chosenEngine),
                overrideStore = store,
                userPicksEngine = { error("resolveWithChosenEngine must not consult the auto fallback") },
            )

        val descriptor = resolver.resolveWithChosenEngine(unknownBundle, "engine-b")

        assertEquals("engine-b", descriptor.engineId)
        assertEquals("engine-b", store.get(unknownBundle.id))

        // A later plain resolve() for the same bundle now reads the saved override, not the fallback.
        val secondDescriptor = resolver.resolve(unknownBundle)
        assertEquals("engine-b", secondDescriptor.engineId)
    }

    // "Handle forcedMatch throwing... with a clear message rather than a crash" - the message itself
    // is whatever the engine reports; this proves it propagates unmangled rather than being swallowed.
    @Test
    fun `resolveWithChosenEngine propagates a forcedMatch rejection instead of swallowing it`() {
        val rejecting =
            FakeEngine(
                id = "engine-a",
                forcedMatchError = IllegalStateException("missing tokenizer.json required by this family"),
            )
        val resolver =
            Resolver(
                engines = listOf(rejecting),
                overrideStore = InMemoryOverrideStore(),
                userPicksEngine = { error("not exercised by this test") },
            )

        val thrown =
            assertFailsWith<IllegalStateException> { resolver.resolveWithChosenEngine(unknownBundle, "engine-a") }
        assertEquals("missing tokenizer.json required by this family", thrown.message)
    }

    @Test
    fun `resolveWithChosenEngine throws for an engine id that isn't registered`() {
        val resolver =
            Resolver(
                engines = listOf(FakeEngine(id = "engine-a")),
                overrideStore = InMemoryOverrideStore(),
                userPicksEngine = { error("not exercised by this test") },
            )

        assertFailsWith<IllegalStateException> { resolver.resolveWithChosenEngine(unknownBundle, "no-such-engine") }
    }
}
