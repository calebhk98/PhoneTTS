package com.phonetts.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Serializes [AppGraph]'s folder-rescan so overlapping [rescan] calls can never let a stale scan of
 * an OLD models folder write catalog entries AFTER a newer `catalog.clear()` - the race that used to
 * leave the model list showing models from a folder the user had already moved away from.
 *
 * The bug: [AppGraph.hydrate] launched its folder scan on a shared scope without retaining the
 * [Job], so it could not be cancelled; [AppGraph.relocateStorage] then called `catalog.clear()`
 * followed by `hydrate()` again. Relocating storage twice quickly - or relocating while the
 * launch-time scan was still running - let the OLD scan keep importing folders (and writing them
 * into the catalog) after the NEWER `clear()` had already run, so the model list ended up showing
 * entries from a folder the user just moved away from.
 *
 * The fix has two parts, deliberately both (the "and/or" in the ticket, applied together for
 * defense in depth):
 *  - The [Job] from the last [rescan] is retained and cancelled before a new one is launched, and
 *    the folder loop checks [ensureActive] before each folder - the same per-item cancellation
 *    checkpoint [com.phonetts.app.hf.HfDownloader.copyCapped] already uses per buffer - so a
 *    cancelled scan stops importing further folders instead of running to completion regardless.
 *  - The scan body is additionally serialized behind a [Mutex], so a fresh [rescan] can't start
 *    writing until any prior scan has actually exited (either by finishing or by unwinding out of
 *    its next [ensureActive] check) - no two scans' writes can interleave.
 */
class CatalogHydrator(
    private val scope: CoroutineScope,
    // Pulled fresh on every [rescan] (not captured once) so a storage-location change already
    // reflected in [folders] before this runs is honored, matching [AppGraph.modelsBaseDir]'s own
    // "read fresh on every call" contract.
    private val folders: () -> List<File>,
    private val importFolder: (String) -> Unit,
) {
    private val mutex = Mutex()
    private var job: Job? = null

    /** Cancel any in-flight scan and launch a fresh one over the CURRENT [folders]. */
    fun rescan() {
        job?.cancel()
        job =
            scope.launch(Dispatchers.IO) {
                mutex.withLock {
                    folders().forEach { folder ->
                        ensureActive()
                        runCatching { importFolder(folder.absolutePath) }
                    }
                }
            }
    }
}
