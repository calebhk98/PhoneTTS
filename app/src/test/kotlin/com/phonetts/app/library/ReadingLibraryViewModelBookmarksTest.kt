package com.phonetts.app.library

import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.AppGraph
import com.phonetts.app.PhoneTtsApplication
import com.phonetts.core.prefs.DocumentBookmarksStore
import com.phonetts.core.text.DocumentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #150 (named bookmarks), exercised through [ReadingLibraryViewModel]'s public API rather
 * than [DocumentBookmarksStore] directly (that pure seam already has its own exhaustive
 * `core/src/test` coverage) - this test's job is only to confirm the ViewModel wiring itself
 * (add/rename/delete/bound/ordering, and the document-delete cleanup) behaves as the screen expects.
 *
 * Uses a REAL [AppGraph] via [PhoneTtsApplication] (mirrors [com.phonetts.app.ScreenSmokeTest] and
 * [com.phonetts.app.backup.BackupStoreFactoryTest]'s established pattern in this repo), not a mock -
 * see those classes' kdocs for why this codebase prefers a real Robolectric `filesDir`/Context over
 * faking `AppGraph`, which nothing else in the app test suite attempts either.
 *
 * [ReadingLibraryViewModel]'s own `bookmarksStore` default is built directly from `graph.appContext`
 * (see that class's kdoc: the ideal home is `AppGraph.documentBookmarks`, a one-line addition to
 * `AppGraph.kt` outside this session's owned files - the exact patch is in the mission report). A
 * fresh [ReadingLibraryViewModel] instance over the SAME [AppGraph] therefore reads/writes the SAME
 * on-disk bookmarks document, which is exactly what [survives a simulated relaunch] below relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingLibraryViewModelBookmarksTest {
    private fun graph(): AppGraph = ApplicationProvider.getApplicationContext<PhoneTtsApplication>().graph

    private fun viewModel(text: String = "") = ReadingLibraryViewModel(graph(), initialText = text)

    @Test
    fun `a newly added bookmark shows up in state once its document's sheet is open`() {
        val vm = viewModel()
        val documentId = DocumentId.of("some document text")

        vm.addBookmark(documentId, "Chapter 2", sentenceIndex = 12)
        vm.openBookmarks(documentId)

        val bookmarks = vm.state.value.bookmarks
        assertEquals(1, bookmarks.size)
        assertEquals("Chapter 2", bookmarks.first().label)
        assertEquals(12, bookmarks.first().sentenceIndex)
    }

    @Test
    fun `a blank label is rejected rather than silently stored`() {
        val vm = viewModel()
        val documentId = DocumentId.of("blank label doc")

        vm.addBookmark(documentId, "   ", sentenceIndex = 1)
        vm.openBookmarks(documentId)

        assertTrue(vm.state.value.bookmarks.isEmpty())
    }

    @Test
    fun `renaming updates the label without moving the bookmark's position`() {
        val vm = viewModel()
        val documentId = DocumentId.of("rename doc")
        vm.addBookmark(documentId, "Old label", sentenceIndex = 5)
        vm.openBookmarks(documentId)
        val id = vm.state.value.bookmarks.single().id

        vm.renameBookmark(documentId, id, "New label")

        val bookmark = vm.state.value.bookmarks.single()
        assertEquals("New label", bookmark.label)
        assertEquals(5, bookmark.sentenceIndex)
    }

    @Test
    fun `deleting one bookmark leaves the others for the same document`() {
        val vm = viewModel()
        val documentId = DocumentId.of("delete doc")
        vm.addBookmark(documentId, "Keep", sentenceIndex = 1)
        vm.addBookmark(documentId, "Drop", sentenceIndex = 2)
        vm.openBookmarks(documentId)
        val dropId = vm.state.value.bookmarks.first { it.label == "Drop" }.id

        vm.deleteBookmark(documentId, dropId)

        assertEquals(listOf("Keep"), vm.state.value.bookmarks.map { it.label })
    }

    @Test
    fun `bookmarks list in document order, not creation order`() {
        val vm = viewModel()
        val documentId = DocumentId.of("ordering doc")

        vm.addBookmark(documentId, "End", sentenceIndex = 90)
        vm.addBookmark(documentId, "Start", sentenceIndex = 0)
        vm.addBookmark(documentId, "Middle", sentenceIndex = 40)
        vm.openBookmarks(documentId)

        assertEquals(listOf("Start", "Middle", "End"), vm.state.value.bookmarks.map { it.label })
    }

    @Test
    fun `closeBookmarks clears the open sheet's state`() {
        val vm = viewModel()
        val documentId = DocumentId.of("close doc")
        vm.addBookmark(documentId, "One", sentenceIndex = 1)
        vm.openBookmarks(documentId)

        vm.closeBookmarks()

        assertNull(vm.state.value.bookmarksOpenFor)
        assertTrue(vm.state.value.bookmarks.isEmpty())
    }

    @Test
    fun `hitting the per-document bound surfaces a status instead of evicting an older bookmark`() {
        val vm = viewModel()
        val documentId = DocumentId.of("bound doc")
        repeat(DocumentBookmarksStore.DEFAULT_MAX_PER_DOCUMENT) { i ->
            vm.addBookmark(documentId, "Mark $i", sentenceIndex = i)
        }

        vm.addBookmark(documentId, "One too many", sentenceIndex = 999)

        vm.openBookmarks(documentId)
        assertEquals(DocumentBookmarksStore.DEFAULT_MAX_PER_DOCUMENT, vm.state.value.bookmarks.size)
        assertTrue(vm.state.value.bookmarks.none { it.label == "One too many" })
        assertEquals(true, vm.state.value.status?.contains("limit", ignoreCase = true))
    }

    @Test
    fun `a bookmark survives a simulated relaunch - a fresh ViewModel over the same graph sees it`() {
        val documentId = DocumentId.of("relaunch doc")
        viewModel().addBookmark(documentId, "Still here", sentenceIndex = 7)

        // A brand new ViewModel instance, exactly like a process restart constructing a fresh
        // ReadingLibraryViewModel over the same AppGraph/on-disk storage.
        val relaunched = viewModel()
        relaunched.openBookmarks(documentId)

        val bookmark = relaunched.state.value.bookmarks.single()
        assertEquals("Still here", bookmark.label)
        assertEquals(7, bookmark.sentenceIndex)
    }

    @Test
    fun `deleting the document forgets its bookmarks too`() {
        val vm = viewModel()
        val documentId = graph().documentLibrary.add(id = "doc-with-bookmark", text = "hello", savedAtMillis = 1L).id
        vm.addBookmark(documentId, "Won't survive delete", sentenceIndex = 1)

        vm.delete(documentId)

        val after = viewModel()
        after.openBookmarks(documentId)
        assertTrue(after.state.value.bookmarks.isEmpty())
    }

    @Test
    fun `refresh reports the bookmark count per row`() {
        val documentId = graph().documentLibrary.add(id = "doc-for-row-count", text = "hello", savedAtMillis = 1L).id
        val vm = viewModel()
        vm.addBookmark(documentId, "A mark", sentenceIndex = 1)

        vm.refresh()

        val row = vm.state.value.rows.first { it.meta.id == documentId }
        assertEquals(1, row.bookmarkCount)
    }
}
