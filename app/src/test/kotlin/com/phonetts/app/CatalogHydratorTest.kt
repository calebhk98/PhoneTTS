package com.phonetts.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test for the overlapping-catalog-hydration race: [AppGraph.hydrate] used to launch its
 * folder scan without retaining the Job, so [AppGraph.relocateStorage] (`catalog.clear()` then
 * `hydrate()` again) could let a stale scan of the OLD folder keep writing entries into the catalog
 * AFTER the newer clear() - leaving the model list showing models from a folder the user had just
 * moved away from. [CatalogHydrator] is the extracted, Android-free piece that fixes it (retained +
 * cancelled Job, plus a Mutex serializing the scan body); this test drives it directly so the
 * invariant is checked without needing a full Android [AppGraph]/Context.
 */
class CatalogHydratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a rescan triggered while an older scan is still mid-flight wins - the stale scan cannot write after it`() {
        val written = CopyOnWriteArrayList<String>()

        // The OLD scan pauses right after importing its first folder, so the test can trigger a
        // newer rescan() while the old one is still "in flight" further down its folder list - the
        // exact overlap that used to let a stale scan outlive a newer clear()/rescan().
        val pausedAfterFirstFolder = CountDownLatch(1)
        val releaseOldScan = CountDownLatch(1)

        val oldFolders = listOf(tmp.newFolder("old-1"), tmp.newFolder("old-2"), tmp.newFolder("old-3"))
        val newFolders = listOf(tmp.newFolder("new-1"))
        var currentFolders = oldFolders

        val scope = CoroutineScope(SupervisorJob())
        val hydrator =
            CatalogHydrator(
                scope = scope,
                folders = { currentFolders },
                importFolder = { path ->
                    written.add(path)
                    if (path.endsWith("old-1")) {
                        pausedAfterFirstFolder.countDown()
                        releaseOldScan.await(5, TimeUnit.SECONDS)
                    }
                },
            )

        hydrator.rescan()
        assertTrue("old scan should have reached and paused after its first folder", pausedAfterFirstFolder.await(5, TimeUnit.SECONDS))

        // Simulate AppGraph.relocateStorage(): clear whatever the (stale, still-paused) scan has
        // written so far, then rescan the NEW folder - while the old scan is still parked mid-loop.
        written.clear()
        currentFolders = newFolders
        hydrator.rescan()

        // Now let the stale scan resume. If cancellation wasn't threaded through, it would go on to
        // import old-2/old-3 and write them into `written` after the "clear" above - reproducing the
        // bug. With the fix, the cancelled Job's next per-folder `ensureActive()` check stops it cold.
        releaseOldScan.countDown()

        awaitUntil(timeoutMs = 5_000) { written.any { it.endsWith("new-1") } }
        // Give any (buggy) straggler writes from the stale scan a chance to land before asserting.
        Thread.sleep(200)

        assertEquals(
            "only the NEW folder should be reflected - the stale scan must not have written after the clear",
            listOf(true),
            written.map { it.endsWith("new-1") }.distinct(),
        )
        assertEquals(1, written.size)
    }

    private fun awaitUntil(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}
