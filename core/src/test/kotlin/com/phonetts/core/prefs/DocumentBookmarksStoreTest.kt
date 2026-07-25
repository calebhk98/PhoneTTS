package com.phonetts.core.prefs

import com.phonetts.core.store.InMemoryDurableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentBookmarksStoreTest {
    @Test
    fun `a document with no bookmarks returns an empty list`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        assertEquals(emptyList(), store.bookmarksFor("doc-1"))
    }

    @Test
    fun `add records a bookmark retrievable by its document`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())

        val result = store.add("doc-1", "Chapter 2", sentenceIndex = 40, createdAtMillis = 1_000L, id = "b1")

        assertTrue(result is BookmarkAddResult.Added)
        val saved = store.bookmarksFor("doc-1")
        assertEquals(1, saved.size)
        assertEquals(DocumentBookmark("b1", "doc-1", "Chapter 2", 40, 1_000L), saved.first())
    }

    @Test
    fun `bookmarks for one document never appear under another`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        store.add("doc-a", "A mark", 5, 1_000L, "b1")
        store.add("doc-b", "B mark", 5, 1_000L, "b2")

        assertEquals(listOf("b1"), store.bookmarksFor("doc-a").map { it.id })
        assertEquals(listOf("b2"), store.bookmarksFor("doc-b").map { it.id })
    }

    @Test
    fun `bookmarksFor orders by sentenceIndex ascending, not creation order`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        // Created out of document order on purpose.
        store.add("doc-1", "End", sentenceIndex = 90, createdAtMillis = 1_000L, id = "end")
        store.add("doc-1", "Start", sentenceIndex = 0, createdAtMillis = 2_000L, id = "start")
        store.add("doc-1", "Middle", sentenceIndex = 40, createdAtMillis = 3_000L, id = "middle")

        val ordered = store.bookmarksFor("doc-1").map { it.id }

        assertEquals(listOf("start", "middle", "end"), ordered)
    }

    @Test
    fun `ties in sentenceIndex order by creation time`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        store.add("doc-1", "Second", sentenceIndex = 10, createdAtMillis = 2_000L, id = "second")
        store.add("doc-1", "First", sentenceIndex = 10, createdAtMillis = 1_000L, id = "first")

        val ordered = store.bookmarksFor("doc-1").map { it.id }

        assertEquals(listOf("first", "second"), ordered)
    }

    @Test
    fun `rename changes the label without touching position or id`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        store.add("doc-1", "Old name", 12, 1_000L, "b1")

        store.rename("b1", "New name")

        val bookmark = store.bookmarksFor("doc-1").single()
        assertEquals("New name", bookmark.label)
        assertEquals(12, bookmark.sentenceIndex)
    }

    @Test
    fun `renaming an unknown id is a harmless no-op`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        store.add("doc-1", "Kept", 1, 1_000L, "b1")

        store.rename("does-not-exist", "New name")

        assertEquals("Kept", store.bookmarksFor("doc-1").single().label)
    }

    @Test
    fun `delete removes just the one bookmark`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        store.add("doc-1", "Keep", 1, 1_000L, "keep")
        store.add("doc-1", "Drop", 2, 1_000L, "drop")

        store.delete("drop")

        assertEquals(listOf("keep"), store.bookmarksFor("doc-1").map { it.id })
    }

    @Test
    fun `deleting an unknown id is a harmless no-op`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        store.add("doc-1", "Kept", 1, 1_000L, "b1")

        store.delete("never-existed")

        assertEquals(1, store.bookmarksFor("doc-1").size)
    }

    @Test
    fun `forgetDocument clears only that document's bookmarks`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore())
        store.add("doc-a", "A", 1, 1_000L, "a1")
        store.add("doc-b", "B", 1, 1_000L, "b1")

        store.forgetDocument("doc-a")

        assertEquals(emptyList(), store.bookmarksFor("doc-a"))
        assertEquals(listOf("b1"), store.bookmarksFor("doc-b").map { it.id })
    }

    @Test
    fun `add refuses past the per-document bound without evicting anything`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore(), maxPerDocument = 2)
        store.add("doc-1", "One", 1, 1_000L, "b1")
        store.add("doc-1", "Two", 2, 1_000L, "b2")

        val result = store.add("doc-1", "Three", 3, 1_000L, "b3")

        assertEquals(BookmarkAddResult.BoundReached, result)
        assertEquals(listOf("b1", "b2"), store.bookmarksFor("doc-1").map { it.id })
    }

    @Test
    fun `the bound is per-document, not global`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore(), maxPerDocument = 1)
        store.add("doc-a", "A", 1, 1_000L, "a1")

        val result = store.add("doc-b", "B", 1, 1_000L, "b1")

        assertTrue(result is BookmarkAddResult.Added)
    }

    @Test
    fun `deleting a bookmark frees a slot under the bound`() {
        val store = DocumentBookmarksStore(InMemoryDurableStore(), maxPerDocument = 1)
        store.add("doc-1", "First", 1, 1_000L, "b1")
        store.delete("b1")

        val result = store.add("doc-1", "Second", 2, 2_000L, "b2")

        assertTrue(result is BookmarkAddResult.Added)
    }

    @Test
    fun `a bookmark survives a simulated relaunch over the same backing document`() {
        val backing = InMemoryDurableStore()
        val firstLaunch = DocumentBookmarksStore(backing)
        firstLaunch.add("doc-1", "Survives relaunch", 25, 1_000L, "b1")

        // A fresh instance over the SAME backing store, exactly like a process restart constructing
        // a new AppGraph over the same on-disk file.
        val secondLaunch = DocumentBookmarksStore(backing)

        val restored = secondLaunch.bookmarksFor("doc-1")
        assertEquals(listOf(DocumentBookmark("b1", "doc-1", "Survives relaunch", 25, 1_000L)), restored)
    }

    @Test
    fun `a corrupt backing document loads as empty rather than throwing`() {
        val backing = InMemoryDurableStore()
        backing.write(DocumentBookmarksStore.DOCUMENT_NAME, "not valid json")

        val store = DocumentBookmarksStore(backing)

        assertEquals(emptyList(), store.bookmarksFor("doc-1"))
    }
}
