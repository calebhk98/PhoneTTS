package com.phonetts.app

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves a Storage Access Framework folder-tree URI (from
 * `ActivityResultContracts.OpenDocumentTree()`) to a plain [java.io.File] the app can read and
 * write directly, so the `:core` download/import pipeline - deliberately `java.io.File`-based,
 * spec: `:core` stays Android-free - can point straight at it with no change of its own (issue
 * #4/#5: model weights surviving an uninstall/reinstall, on internal storage or an SD card).
 *
 * A held SAF permission grant on the tree URI is not itself enough to open that path as a plain
 * `File` outside SAF; that needs the broad `MANAGE_EXTERNAL_STORAGE` ("All files access")
 * permission declared in the manifest - acceptable here since this is a personal, sideloaded app,
 * not a Play-distributed one. [resolve] never guesses past what it can actually verify: if the
 * volume can't be mapped to a real path, or this process still can't read/write it, that is
 * reported as [Resolution.Unusable] rather than silently pretending it will work (the same
 * fail-closed discipline `inspect()` uses for models, spec rule 4).
 */
object StorageLocation {
    /** The outcome of trying to use a picked SAF tree as a plain-`File` models base dir. */
    sealed interface Resolution {
        /** [path] is a real, verified-writable directory - safe to use as the models base dir. */
        data class Usable(val path: String) : Resolution

        /** Couldn't turn the picked folder into something usable; [reason] is shown to the user. */
        data class Unusable(val reason: String) : Resolution
    }

    /** Resolve a picked tree URI (e.g. from `OpenDocumentTree`) to a real, write-checked directory. */
    fun resolve(treeUri: Uri): Resolution {
        val docId =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return Resolution.Unusable("couldn't read this folder's location")
        val colon = docId.indexOf(':')
        if (colon < 0) return Resolution.Unusable("unsupported folder type '$docId'")

        val volumeId = docId.substring(0, colon)
        val relativePath = docId.substring(colon + 1)
        val volumeRoot = volumeRoot(volumeId) ?: return Resolution.Unusable("unknown storage volume '$volumeId'")
        val resolved = if (relativePath.isBlank()) volumeRoot else File(volumeRoot, relativePath)
        return verifyWritable(resolved)
    }

    // "primary" is the device's main shared storage; anything else is a volume id (an SD card's,
    // typically) mounted under /storage. Neither is a guess: both are the OS's own naming scheme for
    // SAF's external storage provider (DocumentsContract), not something this app invents.
    private fun volumeRoot(volumeId: String): File? {
        if (volumeId.equals("primary", ignoreCase = true)) return Environment.getExternalStorageDirectory()
        val candidate = File("/storage/$volumeId")
        return candidate.takeIf { it.exists() }
    }

    // Confirms this process can actually read AND write [dir] as a plain File - required because
    // holding a SAF permission grant on the tree URI does not, by itself, grant raw filesystem
    // access to the resolved path (that needs MANAGE_EXTERNAL_STORAGE, requested separately).
    private fun verifyWritable(dir: File): Resolution {
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Resolution.Unusable("can't create '${dir.absolutePath}' - grant All files access first")
        }
        val marker = File(dir, WRITE_TEST_FILE_NAME)
        val writable = runCatching { marker.writeText("ok") }.isSuccess
        marker.delete()
        if (!writable) {
            return Resolution.Unusable("no write access to '${dir.absolutePath}' - grant All files access first")
        }
        return Resolution.Usable(dir.absolutePath)
    }

    private const val WRITE_TEST_FILE_NAME = ".phonetts_write_test"
}
