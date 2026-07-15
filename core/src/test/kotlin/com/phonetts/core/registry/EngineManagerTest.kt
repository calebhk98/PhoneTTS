package com.phonetts.core.registry

import com.phonetts.core.testing.FakeEngine
import com.phonetts.core.testing.testDescriptor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class EngineManagerTest {
    @Test
    fun switchingToTheFirstEngineLoadsItWithoutUnloadingAnything() =
        runTest {
            val events = mutableListOf<String>()
            val engineA = FakeEngine(id = "a", eventLog = events)
            val registry = EngineRegistry().apply { register(engineA) }
            val manager = EngineManager(registry)

            manager.switchTo("a", testDescriptor("model-a", "a"))

            assertEquals(1, engineA.loadCount)
            assertEquals(0, engineA.unloadCount)
            assertEquals(listOf("load:a"), events)
            assertSame(engineA, manager.currentEngine)
        }

    @Test
    fun switchingFromAToBUnloadsABeforeLoadingB() =
        runTest {
            val events = mutableListOf<String>()
            val engineA = FakeEngine(id = "a", eventLog = events)
            val engineB = FakeEngine(id = "b", eventLog = events)
            val registry =
                EngineRegistry().apply {
                    register(engineA)
                    register(engineB)
                }
            val manager = EngineManager(registry)

            manager.switchTo("a", testDescriptor("model-a", "a"))
            manager.switchTo("b", testDescriptor("model-b", "b"))

            // Exactly one engine loaded at a time: A is unloaded before B is loaded.
            assertEquals(1, engineA.loadCount)
            assertEquals(1, engineA.unloadCount)
            assertEquals(1, engineB.loadCount)
            assertEquals(0, engineB.unloadCount)
            assertEquals(listOf("load:a", "unload:a", "load:b"), events)
            assertSame(engineB, manager.currentEngine)
        }

    @Test
    fun switchingToAnUnregisteredEngineIdThrowsAndLeavesCurrentEngineUntouched() =
        runTest {
            val events = mutableListOf<String>()
            val engineA = FakeEngine(id = "a", eventLog = events)
            val registry = EngineRegistry().apply { register(engineA) }
            val manager = EngineManager(registry)

            manager.switchTo("a", testDescriptor("model-a", "a"))

            assertFailsWith<IllegalStateException> {
                manager.switchTo("missing", testDescriptor("model-x", "missing"))
            }

            // The unknown-id failure must not have unloaded the still-current engine.
            assertEquals(0, engineA.unloadCount)
            assertSame(engineA, manager.currentEngine)
        }

    @Test
    fun currentEngineAndDescriptorAreNullBeforeAnySwitch() {
        val manager = EngineManager(EngineRegistry())
        assertNull(manager.currentEngine)
        assertNull(manager.currentDescriptor)
    }
}
