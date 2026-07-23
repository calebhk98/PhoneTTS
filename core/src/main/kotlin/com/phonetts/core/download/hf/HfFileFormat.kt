package com.phonetts.core.download.hf

/**
 * A weight/model format a browsed Hugging Face repo can ship, derived purely from the repo's file
 * extensions / path tokens (issue #107). No model name or family is named here - the buckets are
 * generic formats, so a repo that starts shipping a new format tomorrow surfaces as a filter choice
 * with no per-model code (spec rule 1 SSOT). Apple-only formats (MLX, CoreML) mirror the same
 * token-based detection [HfCompatibility] uses, since MLX ships as `.safetensors` and CoreML as a
 * directory bundle - neither is a plain file extension.
 */
enum class HfFileFormat {
    ONNX,
    GGUF,
    SAFETENSORS,
    PYTORCH,
    TFLITE,
    NEMO,
    MLX,
    COREML,
}

/**
 * Derives the set of [HfFileFormat]s present in a repo's file tree, and filters/enumerates results
 * by them. Pure and unit-tested in `:core` - the browse layer fetches file trees (same fetch the
 * size estimate already uses) and passes the derived formats in, exactly like it does for sizes.
 */
object HfFileFormats {
    /** Every [HfFileFormat] present in [files]. [repoId] sharpens Apple detection the same way
     * [HfCompatibility.classify] does (an MLX repo ships `.safetensors`, only the namespace proves
     * it). A directory-only or empty tree yields the empty set. */
    fun formatsOf(
        files: List<HfTreeEntry>,
        repoId: String? = null,
    ): Set<HfFileFormat> {
        val paths = files.filter { it.isFile }.map { it.path }
        val formats = mutableSetOf<HfFileFormat>()
        paths.forEach { path -> extensionFormat(path)?.let { formats += it } }
        if (mentions(paths, repoId, MLX_TOKENS)) formats += HfFileFormat.MLX
        if (mentions(paths, repoId, COREML_TOKENS)) formats += HfFileFormat.COREML
        return formats
    }

    /** The format-filter menu's choices: every format actually present across the fetched results,
     * ordered by the enum for a stable menu - derived from data, never a hardcoded list. */
    fun availableFormats(formatsById: Map<String, Set<HfFileFormat>>): List<HfFileFormat> =
        formatsById.values.flatten().distinct().sortedBy { it.ordinal }

    /** Keeps only results whose fetched format set contains [format]; a null [format] is a no-op.
     * Once a format IS selected, a result whose formats aren't fetched yet (absent from
     * [formatsById]) is dropped, mirroring [HfResultsView.filterBySize]'s unknown-excluded rule. */
    fun filterByFormat(
        results: List<HfModelSummary>,
        formatsById: Map<String, Set<HfFileFormat>>,
        format: HfFileFormat?,
    ): List<HfModelSummary> {
        if (format == null) return results
        return results.filter { format in (formatsById[it.id] ?: emptySet()) }
    }

    private fun extensionFormat(path: String): HfFileFormat? =
        when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "onnx" -> HfFileFormat.ONNX
            "gguf" -> HfFileFormat.GGUF
            "safetensors" -> HfFileFormat.SAFETENSORS
            "bin", "pt", "pth", "ckpt" -> HfFileFormat.PYTORCH
            "tflite" -> HfFileFormat.TFLITE
            "nemo" -> HfFileFormat.NEMO
            "mlx" -> HfFileFormat.MLX
            else -> null
        }

    // Delimited-token match (not substring) so "mlx-community" or a `.mlpackage` directory register
    // while an unrelated path that merely contains the letters does not - same approach as
    // HfCompatibility.mentions.
    private fun mentions(
        paths: List<String>,
        repoId: String?,
        tokens: Set<String>,
    ): Boolean {
        val haystacks = paths + listOfNotNull(repoId)
        return haystacks.any { haystack ->
            val parts = haystack.lowercase().split('-', '_', '.', '/', ' ')
            tokens.any { it in parts }
        }
    }

    private val MLX_TOKENS = setOf("mlx")
    private val COREML_TOKENS = setOf("coreml", "mlpackage", "mlmodel", "mlmodelc")
}
