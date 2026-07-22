package com.phonetts.app

import android.os.Build
import java.io.File

/**
 * Where downloaded/sideloaded model folders live on device, and how their names are made
 * filesystem-safe. Shared by the sideload and Hugging Face download paths so the app-private
 * layout is defined in exactly one place (spec §8: weights live in app-private storage).
 */
object ModelStorage {
    const val MODELS_DIR = "models"
    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

    fun sanitize(name: String): String = name.replace(UNSAFE_CHARS, "_")

    /** The app-private directory a model with [name] is stored under, within [filesDir]. */
    fun modelDir(filesDir: File, name: String): File = File(filesDir, "$MODELS_DIR/${sanitize(name)}")

    /**
     * Total size in bytes of every file under the model directory for [name], or 0 if that
     * directory doesn't exist. Used to show per-model storage usage (spec §1.1.6 "removable
     * models" — you can't judge whether removal is worth it without seeing what it costs).
     *
     * Bug #7: [File.listFiles] (what [File.walkTopDown] uses internally) can return `null` — a
     * silent readdir() failure treated as "empty directory" rather than an error — for a
     * directory that [File.isDirectory] (a plain stat()) already confirmed exists. This is a real,
     * documented quirk on scoped/removable storage (an SD card via the relocated storage location,
     * spec issue #4/#5) and on some OEM builds, exactly the budget/SD-card hardware this app
     * targets. That silent-empty result is indistinguishable from a genuinely empty folder, so a
     * fully-downloaded model can report 0 B. [sizeOfTree] recurses by hand and falls back to
     * `java.nio.file` — a different syscall path (`Files.newDirectoryStream`) — whenever the
     * classic `File` listing comes back empty for a directory that demonstrably has content on
     * disk that stat() can already see is there.
     */
    fun sizeBytes(filesDir: File, name: String): Long {
        val dir = modelDir(filesDir, name)
        if (!dir.isDirectory) return 0L
        return sizeOfTree(dir)
    }

    // `File.listFiles()` returning `null` (as opposed to a non-null EMPTY array, which just means
    // "genuinely no entries") is a hard readdir() failure signal, distinct from "empty" — but
    // walkTopDown() silently treats both the same way, as zero. Recurse by hand so a subtree that
    // fails this way is retried via NIO (a different syscall path, `Files.newDirectoryStream` /
    // `Files.walk`) instead of the whole model silently pricing out at 0 B.
    private fun sizeOfTree(dir: File): Long {
        val children = dir.listFiles() ?: return NioFallback.sizeBytesIfAvailable(dir)
        return children.sumOf { child -> if (child.isDirectory) sizeOfTree(child) else child.length() }
    }

    // Isolated in its own object (not inlined into sizeOfTree) so a pre-API-26 runtime — minSdk is
    // 24, and java.nio.file only exists natively from API 26 with no desugaring configured for this
    // module — never has to resolve java.nio.file.* while verifying ModelStorage itself; only
    // touching this object (guarded below) forces that resolution, and only on API 26+.
    private object NioFallback {
        fun sizeBytesIfAvailable(dir: File): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0L
            return runCatching { walk(dir) }.getOrDefault(0L)
        }

        private fun walk(dir: File): Long {
            val path = dir.toPath()
            return java.nio.file.Files.walk(path).use { stream ->
                var total = 0L
                stream.forEach { p -> if (java.nio.file.Files.isRegularFile(p)) total += java.nio.file.Files.size(p) }
                total
            }
        }
    }

    /**
     * Recursively deletes the model directory for [name]. Returns true if it existed and was
     * removed, false if there was nothing there to delete.
     */
    fun delete(filesDir: File, name: String): Boolean {
        val dir = modelDir(filesDir, name)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }
}
