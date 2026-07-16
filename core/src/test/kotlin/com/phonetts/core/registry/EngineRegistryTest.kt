package com.phonetts.core.registry

import com.phonetts.core.testing.FakeEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineRegistryTest {
    @Test
    fun registeringAFakeEngineMakesItAppearInListWithNoOtherChanges() {
        val registry = EngineRegistry()
        assertTrue(registry.list().isEmpty())

        val fake = FakeEngine(id = "fake-a")
        registry.register(fake)

        assertEquals(listOf(fake), registry.list())
        assertEquals(fake, registry.get("fake-a"))
    }

    @Test
    fun unregisterRemovesTheEngine() {
        val registry = EngineRegistry()
        val fake = FakeEngine(id = "fake-b")
        registry.register(fake)

        registry.unregister("fake-b")

        assertTrue(registry.list().isEmpty())
        assertNull(registry.get("fake-b"))
    }

    @Test
    fun unregisterOfUnknownIdIsANoOp() {
        val registry = EngineRegistry()
        registry.unregister("never-registered")
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun registeringUnderAnExistingIdReplacesThePreviousRegistration() {
        val registry = EngineRegistry()
        val first = FakeEngine(id = "dup")
        val second = FakeEngine(id = "dup")

        registry.register(first)
        registry.register(second)

        assertEquals(1, registry.list().size)
        assertEquals(second, registry.get("dup"))
    }

    @Test
    fun getOfUnknownIdReturnsNull() {
        val registry = EngineRegistry()
        assertNull(registry.get("missing"))
    }
}
