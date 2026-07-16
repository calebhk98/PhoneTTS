package com.phonetts.app.sideload

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.sideload.ModelImporter
import java.io.File

/**
 * Android glue for "download a model, then select it and use it" (spec §4 Phase 3).
 *
 * The user picks a folder — e.g. an unzipped Hugging Face model sitting in Downloads — with the
 * Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`). We copy it into app-private storage
 * (spec §8: weights live in app-private storage, never bundled) and hand that plain filesystem
 * path to the tested core [ModelImporter], which reads it with `DirectoryBundleReader`, resolves
 * it (auto-detect that fails closed, else the user-pick fallback), and catalogs it.
 *
 * NO model-specific code lives here: detection and descriptor building are entirely the core
 * resolver's job, so a new model of a known family works with ZERO changes to this class.
 *
 * NOTE: this is the platform half of Phase 3 and requires the Android SDK to compile/run — it
 * is exercised on-device, not by the JVM seam tests. The logic it delegates to (reader → resolve
 * → catalog) is fully covered by :core and :integration tests.
 */
class SideloadCoordinator(
    private val context: Context,
    private val importer: ModelImporter,
) {
    /** Copy the user-picked folder into app-private storage and import it into the catalog. */
    fun importFromTree(treeUri: Uri): ModelDescriptor {
        val picked =
            DocumentFile.fromTreeUri(context, treeUri)
                ?: error("could not open the selected folder")
        val destination = File(context.filesDir, "$MODELS_DIR/${sanitize(picked.name)}")
        copyTree(picked, destination)
        return importer.import(destination.absolutePath)
    }

    private fun copyTree(source: DocumentFile, destination: File) {
        destination.mkdirs()
        for (child in source.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                copyTree(child, File(destination, name))
                continue
            }
            copyFile(child.uri, File(destination, name))
        }
    }

    private fun copyFile(source: Uri, target: File) {
        val input = context.contentResolver.openInputStream(source) ?: error("could not read $source")
        input.use { stream -> target.outputStream().use { out -> stream.copyTo(out) } }
    }

    private fun sanitize(name: String?): String = (name ?: "model").replace(UNSAFE_CHARS, "_")

    companion object {
        private const val MODELS_DIR = "models"
        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
