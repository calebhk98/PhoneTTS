package com.phonetts.core.model

/**
 * A downloaded model bundle sitting in app-private storage — the raw material an engine
 * inspects. Deliberately Android-free: it is just an id, the set of file names present,
 * and the small text "side files" (config.json, tokenizer, phoneme map, speaker table)
 * read into memory for fingerprinting. Large weights are referenced by name only via
 * [rootPath]; they are never loaded here.
 *
 * This shape is what makes `inspect()` unit-testable on a plain JVM: a test constructs a
 * fake bundle from a name set and a couple of side-file strings — no filesystem needed.
 */
data class ModelBundle(
    val id: String,
    val fileNames: Set<String>,
    val sideFiles: Map<String, String> = emptyMap(),
    val rootPath: String? = null,
) {
    /** True if a file with this exact relative name is present in the bundle. */
    fun hasFile(name: String): Boolean = name in fileNames

    /** True if any file name ends with [suffix] (e.g. ".onnx"). */
    fun hasFileEndingWith(suffix: String): Boolean = fileNames.any { it.endsWith(suffix) }

    /** The in-memory contents of a small text side file, or null if it was not present. */
    fun sideFile(name: String): String? = sideFiles[name]
}
