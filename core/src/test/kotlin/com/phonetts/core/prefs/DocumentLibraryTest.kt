package com.phonetts.core.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentLibraryTest {
    @Test
    fun `list is empty before anything is saved`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `add then get round-trips id, title, text and timestamp`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())

        val saved = library.add(id = "doc-1", text = "Hello world.", savedAtMillis = 1000L, title = "My doc")

        assertEquals(LibraryDocument("doc-1", "My doc", "Hello world.", 1000L), saved)
        assertEquals(saved, library.get("doc-1"))
    }

    @Test
    fun `get returns null for a document that was never saved`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        assertNull(library.get("never-saved"))
    }

    @Test
    fun `a null or blank title derives one from the text's first non-blank line`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())

        val fromNull = library.add(id = "doc-1", text = "First line.\nSecond line.", savedAtMillis = 1L, title = null)
        val fromBlank = library.add(id = "doc-2", text = "  \nActual first line.", savedAtMillis = 2L, title = "   ")

        assertEquals("First line.", fromNull.title)
        assertEquals("Actual first line.", fromBlank.title)
    }

    @Test
    fun `derived title truncates a long first line with an ellipsis`() {
        val longLine = "x".repeat(200)
        val title = DocumentLibrary.deriveTitle(longLine)
        assertTrue(title.length <= 61) // 60 chars + the ellipsis char
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `derived title falls back to Untitled for blank text`() {
        assertEquals("Untitled", DocumentLibrary.deriveTitle("   \n   "))
        assertEquals("Untitled", DocumentLibrary.deriveTitle(""))
    }

    @Test
    fun `saving the same id again replaces it rather than duplicating it`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        library.add(id = "doc-1", text = "First version.", savedAtMillis = 1L, title = "V1")

        library.add(id = "doc-1", text = "Second version.", savedAtMillis = 2L, title = "V2")

        assertEquals(1, library.list().size)
        assertEquals("Second version.", library.get("doc-1")?.text)
    }

    @Test
    fun `list orders most recently saved first`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        library.add(id = "old", text = "old text", savedAtMillis = 100L)
        library.add(id = "new", text = "new text", savedAtMillis = 200L)
        library.add(id = "middle", text = "middle text", savedAtMillis = 150L)

        assertEquals(listOf("new", "middle", "old"), library.list().map { it.id })
    }

    @Test
    fun `rename updates the title and leaves everything else unchanged`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        library.add(id = "doc-1", text = "Body text.", savedAtMillis = 1L, title = "Old title")

        val renamed = library.rename("doc-1", "New title")

        assertEquals("New title", renamed?.title)
        assertEquals("Body text.", renamed?.text)
        assertEquals("New title", library.get("doc-1")?.title)
    }

    @Test
    fun `rename returns null for an unknown id or a blank title`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        library.add(id = "doc-1", text = "Body.", savedAtMillis = 1L, title = "Title")

        assertNull(library.rename("nope", "New title"))
        assertNull(library.rename("doc-1", "   "))
        // A rejected rename must not have changed anything.
        assertEquals("Title", library.get("doc-1")?.title)
    }

    @Test
    fun `delete removes a saved document`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        library.add(id = "doc-1", text = "Body.", savedAtMillis = 1L)

        library.delete("doc-1")

        assertNull(library.get("doc-1"))
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `deleting an unsaved id is a harmless no-op`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        library.delete("never-saved")
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `documents persist across separate library instances over the same backing store`() {
        val backing = InMemoryPreferenceStore()
        DocumentLibrary(backing).add(id = "doc-1", text = "Persisted text.", savedAtMillis = 1L, title = "Persisted")

        val reloaded = DocumentLibrary(backing)

        assertEquals("Persisted", reloaded.get("doc-1")?.title)
    }

    @Test
    fun `a malformed record is skipped on read rather than crashing`() {
        val store = InMemoryPreferenceStore()
        library(store).add(id = "good", text = "Good doc.", savedAtMillis = 1L, title = "Good")
        // Inject a corrupt record directly (wrong field count) alongside the good one.
        val existing = store.getStringSet("reading_library")
        store.putStringSet("reading_library", existing + "not-a-valid-record")

        val documents = library(store).list()

        assertEquals(listOf("good"), documents.map { it.id })
    }

    @Test
    fun `text containing many lines and unusual characters round-trips exactly`() {
        val library = DocumentLibrary(InMemoryPreferenceStore())
        val text = "Line one.\nLine two - with an em dash.\n\nLine four, after a blank line."

        library.add(id = "doc-1", text = text, savedAtMillis = 1L, title = "Multi-line")

        assertEquals(text, library.get("doc-1")?.text)
    }

    private fun library(store: PreferenceStore) = DocumentLibrary(store)
}
