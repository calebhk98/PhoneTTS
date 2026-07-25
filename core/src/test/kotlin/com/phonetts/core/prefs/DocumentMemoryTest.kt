package com.phonetts.core.prefs

import com.phonetts.core.engine.Voice
import com.phonetts.core.testing.testDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentMemoryTest {
    @Test
    fun `resume returns null for a document that was never recorded`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        assertNull(memory.resume("doc-1"))
    }

    @Test
    fun `record then resume round-trips engine, voice, speed and sentence position`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        val position = DocumentPosition("doc-1", engineId = "piper", voiceId = "v0", speed = 1.25f, sentenceIndex = 7)

        memory.record(position)

        assertEquals(position, memory.resume("doc-1"))
    }

    @Test
    fun `recording a document again overwrites its previous position`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        memory.record(DocumentPosition("doc-1", "piper", "v0", 1.0f, 2))

        val updated = DocumentPosition("doc-1", "kokoro", "v1", 1.5f, 9)
        memory.record(updated)

        assertEquals(updated, memory.resume("doc-1"))
    }

    @Test
    fun `positions for different documents do not collide`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        val docA = DocumentPosition("doc-a", "piper", "v0", 1.0f, 3)
        val docB = DocumentPosition("doc-b", "kokoro", "v1", 1.5f, 8)

        memory.record(docA)
        memory.record(docB)

        assertEquals(docA, memory.resume("doc-a"))
        assertEquals(docB, memory.resume("doc-b"))
    }

    @Test
    fun `forget clears a recorded position so resume fails closed`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        memory.record(DocumentPosition("doc-1", "piper", "v0", 1.0f, 2))

        memory.forget("doc-1")

        assertNull(memory.resume("doc-1"))
    }

    @Test
    fun `forgetting an unrecorded document is a harmless no-op`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        memory.forget("never-recorded")
        assertNull(memory.resume("never-recorded"))
    }

    @Test
    fun `resume fails closed on a partially written record`() {
        val store = InMemoryPreferenceStore()
        // Simulate a corrupt/partial write: only the engine field made it to the store.
        store.putString("document_position.doc-1.engineId", "piper")

        val memory = DocumentMemory(store)

        assertNull(memory.resume("doc-1"))
    }

    @Test
    fun `record then resume round-trips the modelId too`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        val position = DocumentPosition("doc-1", "piper", "v0", 1.0f, 3, modelId = "piper-amy")

        memory.record(position)

        assertEquals("piper-amy", memory.resume("doc-1")?.modelId)
    }

    @Test
    fun `resume still succeeds with a null modelId for a legacy position`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        memory.record(DocumentPosition("doc-1", "piper", "v0", 1.0f, 3))

        val resumed = memory.resume("doc-1")

        assertEquals(3, resumed?.sentenceIndex)
        assertNull(resumed?.modelId)
    }

    // --- preferredSelection (issue #147: genuine per-document voice/speed memory) ------------------

    @Test
    fun `preferredSelection is null when nothing was ever recorded for the document`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        val models = listOf(testDescriptor("piper-amy", "piper"))

        assertNull(memory.preferredSelection("doc-1", models))
    }

    @Test
    fun `preferredSelection restores the recorded model, voice and speed`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        val voice = Voice("v1", "Voice 1", "en")
        val descriptor =
            testDescriptor("piper-amy", "piper", voices = listOf(voice), speedRange = 0.5f..2.0f)
        memory.record(DocumentPosition("doc-1", "piper", "v1", 1.5f, 4, modelId = "piper-amy"))

        val restored = memory.preferredSelection("doc-1", listOf(descriptor))

        assertEquals(descriptor, restored?.descriptor)
        assertEquals("v1", restored?.voiceId)
        assertEquals(1.5f, restored?.speed)
    }

    @Test
    fun `preferredSelection fails closed to null when the recorded model was deleted`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        memory.record(DocumentPosition("doc-1", "piper", "v0", 1.5f, 4, modelId = "piper-amy"))

        // The catalog no longer has "piper-amy" - e.g. the model/sideload was removed since.
        val currentModels = listOf(testDescriptor("kokoro-default", "kokoro"))

        assertNull(memory.preferredSelection("doc-1", currentModels))
    }

    @Test
    fun `preferredSelection is null for a legacy record with no modelId, even if the engine still exists`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        // Pre-#147 write: engineId only, no modelId - engineId alone can't identify which of several
        // models sharing that engine this was, so this must fall through to the caller's global fallback.
        memory.record(DocumentPosition("doc-1", "piper", "v0", 1.0f, 4))

        val models = listOf(testDescriptor("piper-amy", "piper"))

        assertNull(memory.preferredSelection("doc-1", models))
    }

    @Test
    fun `preferredSelection falls back to the model's default voice when the saved voice is gone`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        val descriptor = testDescriptor("piper-amy", "piper")
        memory.record(DocumentPosition("doc-1", "piper", "voice-that-no-longer-exists", 1.0f, 4, modelId = "piper-amy"))

        val restored = memory.preferredSelection("doc-1", listOf(descriptor))

        assertEquals(descriptor.defaultVoiceId, restored?.voiceId)
    }

    @Test
    fun `preferredSelection falls back to the model's default speed when the saved speed is out of range`() {
        val memory = DocumentMemory(InMemoryPreferenceStore())
        val descriptor = testDescriptor("piper-amy", "piper", speedRange = 0.5f..2.0f)
        memory.record(DocumentPosition("doc-1", "piper", "v0", 99f, 4, modelId = "piper-amy"))

        val restored = memory.preferredSelection("doc-1", listOf(descriptor))

        assertEquals(descriptor.defaultSpeed, restored?.speed)
    }
}
