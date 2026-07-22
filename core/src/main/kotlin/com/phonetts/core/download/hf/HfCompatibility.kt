package com.phonetts.core.download.hf

/**
 * A conservative, file-tree-only heuristic for whether a browsed repo has anything this app's
 * *currently registered* engines could actually load. Used by the Browse screen to grey-out a
 * "Not yet supported" badge — it never blocks the download itself, since the user may deliberately
 * want the weights on disk ahead of a future engine (spec rule 1: no hardcoded model-name list —
 * this only recognizes the file extensions the app's own runtimes already read):
 *
 * - The ONNX `Runtime` engines (MeloTTS, Piper, KittenTTS, Kokoro, ...) all load `.onnx` graphs.
 * - `NativeTtsRuntime` (CosyVoice3) loads a stack of `.gguf` files (see `BuiltInCatalog`'s
 *   CosyVoice3 entry, which ships `cosyvoice3-*.gguf`).
 *
 * A repo with neither is not necessarily broken — it might be a PyTorch-only repo (e.g. safetensors
 * weights) this app doesn't run yet — so the caller should present this as an honest "may not run
 * yet" label, never a hard block.
 */
object HfCompatibility {
    private val RUNNABLE_EXTENSIONS = setOf("onnx", "gguf")

    /** True if [files] contains at least one file whose extension a registered runtime in this app
     * can load today. */
    fun hasRunnableFiles(files: List<HfTreeEntry>): Boolean = files.any { it.isFile && isRunnableExtension(it.path) }

    private fun isRunnableExtension(path: String): Boolean {
        val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in RUNNABLE_EXTENSIONS
    }
}
