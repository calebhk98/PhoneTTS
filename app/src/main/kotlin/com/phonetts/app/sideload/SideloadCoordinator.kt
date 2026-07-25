package com.phonetts.app.sideload

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.phonetts.app.ModelStorage
import com.phonetts.core.download.CappedCopy
import com.phonetts.core.download.SafePath
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.sideload.ModelImporter
import java.io.File

/**
 * Android glue for "download a model, then select it and use it" (spec §4 Phase 3).
 *
 * The user picks a folder - e.g. an unzipped Hugging Face model sitting in Downloads - with the
 * Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`). We copy it into app-private storage
 * (spec §8: weights live in app-private storage, never bundled) and hand that plain filesystem
 * path to the tested core [ModelImporter], which reads it with `DirectoryBundleReader`, resolves
 * it (auto-detect that fails closed, else the user-pick fallback), and catalogs it.
 *
 * NO model-specific code lives here: detection and descriptor building are entirely the core
 * resolver's job, so a new model of a known family works with ZERO changes to this class.
 *
 * Shares [CappedCopy] (built on [com.phonetts.core.download.DownloadStorageCap]) with
 * [com.phonetts.app.hf.HfDownloader] so every ingestion path (a Hugging Face download AND a
 * sideloaded folder picked via the file picker) is guarded by the identical free-space rule - a
 * user importing a huge folder can no longer silently fill the device the way an unguarded
 * `copyTo` previously allowed.
 *
 * NOTE: this is the platform half of Phase 3 and requires the Android SDK to compile/run - it
 * is exercised on-device, not by the JVM seam tests. The logic it delegates to (reader → resolve
 * → catalog) is fully covered by :core and :integration tests.
 */
class SideloadCoordinator(
    private val context: Context,
    private val importer: ModelImporter,
    // Read fresh each import (not cached) so a folder is copied under the CURRENT model-storage base
    // even after the user relocates storage to an SD card (issues #4/#5) - the same base the
    // downloader writes to and Manage sizes/deletes/scans, so a sideloaded model never lands in a
    // stale internal dir where it would size as 0 B and be invisible to the catalog scan.
    private val modelsBaseDir: () -> File,
    // Injectable so the guard stays deterministic in tests, exactly like HfDownloader's own
    // freeBytes hook. Reads the CURRENT base dir's filesystem (not a fixed one) for the same
    // SD-card-relocation reason as [modelsBaseDir] above.
    private val freeBytes: () -> Long = { StatFs(modelsBaseDir().apply { mkdirs() }.path).availableBytes },
) {
    /** Copy the user-picked folder into app-private storage and import it into the catalog. */
    fun importFromTree(treeUri: Uri): ModelDescriptor {
        val picked =
            DocumentFile.fromTreeUri(context, treeUri)
                ?: error("could not open the selected folder")
        val destination = ModelStorage.modelDir(modelsBaseDir(), picked.name ?: "model")
        copyTree(picked, destination)
        return importer.import(destination.absolutePath)
    }

    // internal (not private) so a test in this same module can drive it directly against a
    // DocumentFile.fromFile(...) tree without needing a real SAF/DocumentsProvider backing a
    // treeUri - exercising the same free-space guard importFromTree relies on.
    internal fun copyTree(
        source: DocumentFile,
        destination: File,
    ) {
        destination.mkdirs()
        for (child in source.listFiles()) {
            val name = child.name ?: continue
            if (!SafePath.isSafe(name)) continue // skip entries that could escape the model folder
            if (child.isDirectory) {
                copyTree(child, File(destination, name))
                continue
            }
            copyFile(child.uri, File(destination, name), child.length())
        }
    }

    // Same free-space guard HfDownloader applies to a network download (spec: never let ingestion
    // fill the device to 0 bytes), via the shared [CappedCopy] helper - upfront against the
    // SAF-reported [advertisedLength] (skipped when that's <= 0 - some providers don't report a
    // size, same "unknown size" case the download path's upfront check already tolerates), AND
    // while streaming, since a provider's advertised length is not a guarantee of the real byte
    // count.
    private fun copyFile(
        source: Uri,
        target: File,
        advertisedLength: Long,
    ) {
        val input = context.contentResolver.openInputStream(source) ?: error("could not read $source")
        input.use { stream ->
            target.outputStream().use { out ->
                CappedCopy.copy(stream, out, advertisedLength, target.name, freeBytes)
            }
        }
    }
}
