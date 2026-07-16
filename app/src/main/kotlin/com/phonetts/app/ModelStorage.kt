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
}
