package com.phonetts.core.prefs

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
}
