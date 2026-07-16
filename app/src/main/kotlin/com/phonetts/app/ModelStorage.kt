package com.phonetts.app

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
     */
    fun sizeBytes(filesDir: File, name: String): Long {
        val dir = modelDir(filesDir, name)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
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
