package com.phonetts.core.prefs

import com.phonetts.core.store.DurableStore
import com.phonetts.core.store.InMemoryDurableStore
import com.phonetts.core.text.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentLibraryTest {
    @Test
    fun `list is empty before anything is saved`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `add then get round-trips id, title, text and timestamp`() {
        val library = DocumentLibrary(InMemoryDurableStore())

        val saved = library.add(id = "doc-1", text = "Hello world.", savedAtMillis = 1000L, title = "My doc")

        assertEquals(LibraryDocument("doc-1", "My doc", "Hello world.", 1000L), saved)
        assertEquals(saved, library.get("doc-1"))
    }

    @Test
    fun `get returns null for a document that was never saved`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        assertNull(library.get("never-saved"))
    }

    @Test
    fun `a null or blank title derives one from the text's first non-blank line`() {
        val library = DocumentLibrary(InMemoryDurableStore())

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
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "doc-1", text = "First version.", savedAtMillis = 1L, title = "V1")

        library.add(id = "doc-1", text = "Second version.", savedAtMillis = 2L, title = "V2")

        assertEquals(1, library.list().size)
        assertEquals("Second version.", library.get("doc-1")?.text)
    }

    @Test
    fun `list orders most recently saved first by default`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "old", text = "old text", savedAtMillis = 100L)
        library.add(id = "new", text = "new text", savedAtMillis = 200L)
        library.add(id = "middle", text = "middle text", savedAtMillis = 150L)

        assertEquals(listOf("new", "middle", "old"), library.list().map { it.id })
    }

    @Test
    fun `list honors every LibrarySort option`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "b", text = "short", savedAtMillis = 200L, title = "Bravo")
        library.add(id = "a", text = "a much longer body of text", savedAtMillis = 100L, title = "Alpha")

        assertEquals(listOf("b", "a"), library.list(LibrarySort.RECENT).map { it.id })
        assertEquals(listOf("a", "b"), library.list(LibrarySort.OLDEST).map { it.id })
        assertEquals(listOf("a", "b"), library.list(LibrarySort.TITLE).map { it.id })
        assertEquals(listOf("a", "b"), library.list(LibrarySort.LONGEST).map { it.id })
    }

    @Test
    fun `rename updates the title and leaves everything else unchanged`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "doc-1", text = "Body text.", savedAtMillis = 1L, title = "Old title")

        val renamed = library.rename("doc-1", "New title")

        assertEquals("New title", renamed?.title)
        assertEquals("New title", library.get("doc-1")?.title)
        assertEquals("Body text.", library.get("doc-1")?.text)
    }

    @Test
    fun `rename returns null for an unknown id or a blank title`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "doc-1", text = "Body.", savedAtMillis = 1L, title = "Title")

        assertNull(library.rename("nope", "New title"))
        assertNull(library.rename("doc-1", "   "))
        // A rejected rename must not have changed anything.
        assertEquals("Title", library.get("doc-1")?.title)
    }

    @Test
    fun `delete removes a saved document`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "doc-1", text = "Body.", savedAtMillis = 1L)

        library.delete("doc-1")

        assertNull(library.get("doc-1"))
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `deleting an unsaved id is a harmless no-op`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.delete("never-saved")
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `deleting a document also frees its text document, not just its metadata`() {
        val store = InMemoryDurableStore()
        val library = DocumentLibrary(store)
        library.add(id = "doc-1", text = "Body.", savedAtMillis = 1L)

        library.delete("doc-1")

        assertNull(store.read("${DocumentLibrary.TEXT_DOCUMENT_PREFIX}doc-1"))
    }

    @Test
    fun `documents persist across separate library instances over the same backing store`() {
        val backing = InMemoryDurableStore()
        DocumentLibrary(backing).add(id = "doc-1", text = "Persisted text.", savedAtMillis = 1L, title = "Persisted")

        val reloaded = DocumentLibrary(backing)

        assertEquals("Persisted", reloaded.get("doc-1")?.title)
    }

    @Test
    fun `text containing many lines and unusual characters round-trips exactly`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        val text = "Line one.\nLine two - with an em dash.\n\nLine four, after a blank line."

        library.add(id = "doc-1", text = text, savedAtMillis = 1L, title = "Multi-line")

        assertEquals(text, library.get("doc-1")?.text)
    }

    // --- issue #139: metadata index vs text storage -------------------------------------------------

    @Test
    fun `listing never reads any document's text from the backing store`() {
        // A DurableStore whose read() blows up for anything but the metadata index - list() must
        // never call it for a document's text, only for the small bounded index document.
        val store =
            object : DurableStore {
                private val inner = InMemoryDurableStore()

                override fun read(name: String): String? {
                    check(name == DocumentLibrary.INDEX_DOCUMENT) { "list() read a document's text: $name" }
                    return inner.read(name)
                }

                override fun write(
                    name: String,
                    contents: String,
                ) = inner.write(name, contents)

                override fun delete(name: String) = inner.delete(name)
            }
        val library = DocumentLibrary(store)
        library.add(id = "doc-1", text = "a".repeat(10_000), savedAtMillis = 1L, title = "Big book")
        library.add(id = "doc-2", text = "b".repeat(10_000), savedAtMillis = 2L, title = "Another big book")

        val metas = library.list()

        assertEquals(2, metas.size)
        assertEquals(10_000, metas.first { it.id == "doc-1" }.length)
    }

    @Test
    fun `metaFor never reads the document's text`() {
        val store = InMemoryDurableStore()
        val library = DocumentLibrary(store)
        library.add(id = "doc-1", text = "some body text", savedAtMillis = 1L, title = "Title")

        val meta = library.metaFor("doc-1")

        assertEquals("Title", meta?.title)
        assertEquals(14, meta?.length)
    }

    @Test
    fun `renaming a document never rewrites another document's text`() {
        val store = InMemoryDurableStore()
        val library = DocumentLibrary(store)
        library.add(id = "doc-1", text = "unchanged text", savedAtMillis = 1L, title = "One")
        library.add(id = "doc-2", text = "also unchanged", savedAtMillis = 2L, title = "Two")
        val textDocBefore = store.read("${DocumentLibrary.TEXT_DOCUMENT_PREFIX}doc-2")

        library.rename("doc-1", "Renamed")

        assertEquals(textDocBefore, store.read("${DocumentLibrary.TEXT_DOCUMENT_PREFIX}doc-2"))
    }

    @Test
    fun `a cap past DEFAULT_MAX_ENTRIES evicts the oldest document's metadata AND text`() {
        val store = InMemoryDurableStore()
        val library = DocumentLibrary(store, maxEntries = 2)
        library.add(id = "oldest", text = "oldest text", savedAtMillis = 1L)
        library.add(id = "middle", text = "middle text", savedAtMillis = 2L)

        library.add(id = "newest", text = "newest text", savedAtMillis = 3L)

        assertEquals(2, library.list().size)
        assertNull(library.get("oldest"))
        assertNull(store.read("${DocumentLibrary.TEXT_DOCUMENT_PREFIX}oldest"))
        assertTrue(library.list().map { it.id }.containsAll(listOf("middle", "newest")))
    }

    // --- issue #140: collision resistance ------------------------------------------------------------

    @Test
    fun `two different documents whose text collided under the old hash id are both saved, not overwritten`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        // "Aa" and "BB" collide under java String#hashCode (both 2112) - the exact old-scheme bug
        // issue #140 describes. Saved under DocumentId.of ids, they must both survive.
        val idA = DocumentId.of("Aa")
        val idB = DocumentId.of("BB")

        library.add(id = idA, text = "Aa", savedAtMillis = 1L, title = "Doc A")
        library.add(id = idB, text = "BB", savedAtMillis = 2L, title = "Doc B")

        assertEquals(2, library.list().size)
        assertEquals("Aa", library.get(idA)?.text)
        assertEquals("BB", library.get(idB)?.text)
    }

    // --- search (issue #139) -------------------------------------------------------------------------

    @Test
    fun `search matches by title, case-insensitively`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "doc-1", text = "irrelevant body", savedAtMillis = 1L, title = "The Great Adventure")
        library.add(id = "doc-2", text = "irrelevant body", savedAtMillis = 2L, title = "A Quiet Life")

        val results = library.search("great")

        assertEquals(listOf("doc-1"), results.map { it.id })
    }

    @Test
    fun `search matches by content when the title doesn't match`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "doc-1", text = "the dragon flew over the mountain", savedAtMillis = 1L, title = "Chapter One")
        library.add(id = "doc-2", text = "a calm afternoon in the park", savedAtMillis = 2L, title = "Chapter Two")

        val results = library.search("dragon")

        assertEquals(listOf("doc-1"), results.map { it.id })
    }

    @Test
    fun `a blank search query returns everything, same as list`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "doc-1", text = "one", savedAtMillis = 1L)
        library.add(id = "doc-2", text = "two", savedAtMillis = 2L)

        assertEquals(library.list().map { it.id }, library.search("   ").map { it.id })
    }

    @Test
    fun `search with no matches returns an empty list`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "doc-1", text = "one", savedAtMillis = 1L, title = "Alpha")

        assertTrue(library.search("nonexistent-term").isEmpty())
    }

    @Test
    fun `search honors the requested sort`() {
        val library = DocumentLibrary(InMemoryDurableStore())
        library.add(id = "a", text = "match here", savedAtMillis = 100L, title = "Alpha match")
        library.add(id = "b", text = "match here too", savedAtMillis = 200L, title = "Bravo match")

        assertEquals(listOf("a", "b"), library.search("match", LibrarySort.TITLE).map { it.id })
        assertEquals(listOf("b", "a"), library.search("match", LibrarySort.RECENT).map { it.id })
    }
}
