package com.phonetts.core.prefs

import com.phonetts.core.store.InMemoryDurableStore
import com.phonetts.core.text.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentLibraryMigrationTest {
    // The exact legacy encoding the pre-#139 DocumentLibrary used: one delimited record per document
    // in a StringSet under "reading_library", fields joined by U+001F.
    private fun legacyRecord(
        id: String,
        title: String,
        savedAtMillis: Long,
        text: String,
    ): String = listOf(id, title, savedAtMillis.toString(), text).joinToString("")

    private fun seedLegacy(
        prefs: PreferenceStore,
        vararg records: String,
    ) {
        prefs.putStringSet("reading_library", records.toSet())
    }

    @Test
    fun `migrating an empty legacy store is a harmless no-op`() {
        val prefs = InMemoryPreferenceStore()
        val library = DocumentLibrary(InMemoryDurableStore())
        val memory = DocumentMemory(InMemoryPreferenceStore())

        val result = DocumentLibraryMigration.migrate(prefs, library, memory)

        assertEquals(0, result.migratedDocuments)
        assertTrue(result.alreadyDone)
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `migrates a legacy document onto the new SHA-256 id, preserving title, text and timestamp`() {
        val prefs = InMemoryPreferenceStore()
        val legacyText = "Once upon a time."
        val legacyId = legacyText.hashCode().toString() // the OLD scheme's id
        seedLegacy(prefs, legacyRecord(legacyId, "My Story", 1000L, legacyText))
        val library = DocumentLibrary(InMemoryDurableStore())
        val memory = DocumentMemory(InMemoryPreferenceStore())

        val result = DocumentLibraryMigration.migrate(prefs, library, memory)

        assertEquals(1, result.migratedDocuments)
        assertTrue(!result.alreadyDone)
        val newId = DocumentId.of(legacyText)
        val migrated = library.get(newId)
        assertEquals("My Story", migrated?.title)
        assertEquals(legacyText, migrated?.text)
        assertEquals(1000L, migrated?.savedAtMillis)
        // The document is no longer reachable under its old id - the new id is a different scheme.
        assertNull(library.get(legacyId))
    }

    @Test
    fun `migration deletes the legacy StringSet key once it succeeds`() {
        val prefs = InMemoryPreferenceStore()
        seedLegacy(prefs, legacyRecord("123", "Title", 1L, "Body"))
        val library = DocumentLibrary(InMemoryDurableStore())
        val memory = DocumentMemory(InMemoryPreferenceStore())

        DocumentLibraryMigration.migrate(prefs, library, memory)

        assertTrue(prefs.getStringSet("reading_library").isEmpty())
    }

    @Test
    fun `migration re-keys a matching DocumentMemory resume position onto the new id`() {
        val prefs = InMemoryPreferenceStore()
        val legacyText = "Chapter one begins here."
        val legacyId = legacyText.hashCode().toString()
        seedLegacy(prefs, legacyRecord(legacyId, "Chapter One", 5L, legacyText))
        val library = DocumentLibrary(InMemoryDurableStore())
        val memory = DocumentMemory(InMemoryPreferenceStore())
        val position = DocumentPosition(legacyId, engineId = "piper", voiceId = "v0", speed = 1.25f, sentenceIndex = 3)
        memory.record(position)

        DocumentLibraryMigration.migrate(prefs, library, memory)

        val newId = DocumentId.of(legacyText)
        val resumed = memory.resume(newId)
        assertEquals("piper", resumed?.engineId)
        assertEquals(3, resumed?.sentenceIndex)
        assertEquals(newId, resumed?.documentId)
        // The old key no longer holds anything - re-keyed, not duplicated.
        assertNull(memory.resume(legacyId))
    }

    @Test
    fun `an unsaved document's resume position can't be re-keyed, but migration leaves it untouched, not lost`() {
        // No corresponding library text exists anywhere to re-derive a new id from - this is the one
        // documented, unavoidable gap (see DocumentLibraryMigration's kdoc): migration can only ever
        // leave this position exactly as it was (still readable under its OLD id) rather than losing
        // it outright or throwing.
        val prefs = InMemoryPreferenceStore()
        seedLegacy(prefs, legacyRecord("999", "Saved Doc", 1L, "Saved doc text"))
        val library = DocumentLibrary(InMemoryDurableStore())
        val memory = DocumentMemory(InMemoryPreferenceStore())
        // An orphaned resume position: no library entry with this id.
        val orphan = DocumentPosition("orphan-id", "piper", "v0", 1.0f, 4)
        memory.record(orphan)

        val result = DocumentLibraryMigration.migrate(prefs, library, memory)

        assertEquals(1, result.migratedDocuments)
        // Untouched, not silently dropped - migration never had a new id to move it to.
        assertEquals(orphan, memory.resume("orphan-id"))
    }

    @Test
    fun `running migrate twice is idempotent - the second call is a no-op that changes nothing`() {
        val prefs = InMemoryPreferenceStore()
        val legacyText = "Idempotency check."
        val legacyId = legacyText.hashCode().toString()
        seedLegacy(prefs, legacyRecord(legacyId, "Idempotent Doc", 1L, legacyText))
        val library = DocumentLibrary(InMemoryDurableStore())
        val memory = DocumentMemory(InMemoryPreferenceStore())
        memory.record(DocumentPosition(legacyId, "piper", "v0", 1.0f, 2))

        val first = DocumentLibraryMigration.migrate(prefs, library, memory)
        val second = DocumentLibraryMigration.migrate(prefs, library, memory)

        assertEquals(1, first.migratedDocuments)
        assertTrue(!first.alreadyDone)
        assertEquals(0, second.migratedDocuments)
        assertTrue(second.alreadyDone)
        // State after the second (no-op) call must be identical to right after the first.
        val newId = DocumentId.of(legacyText)
        assertEquals(1, library.list().size)
        assertEquals(legacyText, library.get(newId)?.text)
        assertEquals(2, memory.resume(newId)?.sentenceIndex)
    }

    @Test
    fun `migrating several legacy documents preserves each one independently`() {
        val prefs = InMemoryPreferenceStore()
        seedLegacy(
            prefs,
            legacyRecord("1", "First", 10L, "First document text."),
            legacyRecord("2", "Second", 20L, "Second document text."),
            legacyRecord("3", "Third", 30L, "Third document text."),
        )
        val library = DocumentLibrary(InMemoryDurableStore())
        val memory = DocumentMemory(InMemoryPreferenceStore())

        val result = DocumentLibraryMigration.migrate(prefs, library, memory)

        assertEquals(3, result.migratedDocuments)
        assertEquals(3, library.list().size)
        assertEquals("First document text.", library.get(DocumentId.of("First document text."))?.text)
        assertEquals("Second document text.", library.get(DocumentId.of("Second document text."))?.text)
        assertEquals("Third document text.", library.get(DocumentId.of("Third document text."))?.text)
    }

    @Test
    fun `a malformed legacy record is skipped rather than crashing the whole migration`() {
        val prefs = InMemoryPreferenceStore()
        seedLegacy(prefs, legacyRecord("1", "Good", 1L, "Good text."), "not-a-valid-record")
        val library = DocumentLibrary(InMemoryDurableStore())
        val memory = DocumentMemory(InMemoryPreferenceStore())

        val result = DocumentLibraryMigration.migrate(prefs, library, memory)

        assertEquals(1, result.migratedDocuments)
        assertEquals("Good text.", library.get(DocumentId.of("Good text."))?.text)
    }
}
