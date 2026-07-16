package com.phonetts.core.download

/**
 * Guards against path traversal when reconstructing a downloaded/sideloaded folder from
 * untrusted file paths (e.g. a Hugging Face repo's file list, or a picked archive). A relative
 * path that is absolute or contains a `..` segment could escape the intended model directory and
 * overwrite other app-private files — so those are refused, fail-closed.
 */
object SafePath {
    /** True if [relativePath] is a safe, within-directory relative path (no absolute, no `..`). */
    fun isSafe(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) return false
        if (relativePath.length >= 2 && relativePath[1] == ':') return false // Windows drive letter
        val segments = relativePath.split('/', '\\')
        return segments.none { it == ".." || it.isBlank() }
    }

    /** Throws [IllegalArgumentException] if [relativePath] could escape its base directory. */
    fun require(relativePath: String) {
        require(isSafe(relativePath)) { "unsafe relative path (possible traversal): '$relativePath'" }
    }
}
