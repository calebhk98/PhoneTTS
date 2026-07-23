package com.phonetts.core.sideload

import com.phonetts.core.model.ModelBundle
import java.io.File

/**
 * Reads a filesystem directory (e.g. an unzipped Hugging Face model folder the user copied in)
 * into a [ModelBundle]: every file becomes a name, and small text "side files" (config.json,
 * tokenizer, phoneme map, voice table) are read into memory for fingerprinting while large
 * weights are referenced by name only. This is the JVM-testable half of the auto-load path; the
 * Android SAF-backed equivalent produces the same [ModelBundle] shape.
 */
class DirectoryBundleReader(
    private val maxSideFileBytes: Long = DEFAULT_MAX_SIDE_FILE_BYTES,
) : BundleReader {
    override fun read(location: String): ModelBundle {
        val root = File(location)
        require(root.isDirectory) { "sideload location is not a directory: $location" }

        val files = root.walkTopDown().filter { it.isFile }.toList()
        val names = files.map { it.relativeName(root) }.toSet()
        val sideFiles =
            files
                .filter { isSideFile(it) }
                .associate { it.relativeName(root) to it.readText() }

        return ModelBundle(id = root.name, fileNames = names, sideFiles = sideFiles, rootPath = root.absolutePath)
    }

    // A file is a side file if it is non-empty, within the size cap, and not a known weight
    // format. The size cap is applied UNIFORMLY (including known text extensions) so an oversized
    // config.json/tokens.txt - accidental or hostile - can't be read whole into memory and OOM.
    private fun isSideFile(file: File): Boolean {
        if (file.extension.lowercase() in WEIGHT_EXTENSIONS) return false
        return file.length() in 1..maxSideFileBytes
    }

    private fun File.relativeName(root: File): String = relativeTo(root).path.replace(File.separatorChar, '/')

    companion object {
        const val DEFAULT_MAX_SIDE_FILE_BYTES = 1_000_000L
        private val WEIGHT_EXTENSIONS = setOf("onnx", "bin", "npz", "pt", "pth", "safetensors")
    }
}
