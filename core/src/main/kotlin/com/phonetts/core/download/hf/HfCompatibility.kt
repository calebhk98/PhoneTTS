package com.phonetts.core.download.hf

/**
 * How runnable a browsed repo's files are on THIS app, so the Browse screen can say something honest
 * instead of a single "Not yet supported" that wrongly implies everything is merely unimplemented
 * (issue #108). Three outcomes, all derived from file extensions / patterns only (spec rule 1: no
 * hardcoded model-name list):
 *
 * - [RUNNABLE]: the repo ships something a registered runtime loads today - an `.onnx` graph, or a
 *   native GGUF stack (either a `<name>.gguf` with its `<name>.gguf.json` manifest, per
 *   `GgmlTtsEngine`, or the full multi-stage native pipeline `CosyVoice2Engine` requires).
 * - [NEEDS_CONVERSION]: a format that could, with offline work, become runnable here - raw PyTorch /
 *   safetensors, NVIDIA NeMo (`.nemo`), TensorFlow Lite (`.tflite`), or a bare `.gguf` missing the
 *   manifest the ggml engine needs. Not "coming soon" inside the app; the user converts it first.
 * - [IMPOSSIBLE]: Apple-only formats - MLX (Metal) and CoreML (Neural Engine). No Android runtime
 *   exists for these and none ever will in this app; the honest path is an ONNX sibling.
 *
 * The badge never blocks the download itself - the user may deliberately want the weights on disk.
 */
enum class RunCompatibility {
    RUNNABLE,
    NEEDS_CONVERSION,
    IMPOSSIBLE,
}

object HfCompatibility {
    /**
     * True if [files] contains at least one file whose extension a registered runtime in this app can
     * load today. Kept for existing callers; equivalent to [classify] returning [RunCompatibility.RUNNABLE].
     */
    fun hasRunnableFiles(files: List<HfTreeEntry>): Boolean = classify(files) == RunCompatibility.RUNNABLE

    /**
     * Classify a repo's file tree into the three [RunCompatibility] buckets. [repoId] (e.g.
     * `mlx-community/Kokoro-82M`) is optional but sharpens Apple detection, since MLX weights ship as
     * `.safetensors` and are otherwise indistinguishable from a convertible PyTorch export by
     * extension alone. Precedence: a genuinely runnable file wins over everything; an explicit
     * Apple-only signal wins over an otherwise-convertible original; convertible is the default.
     */
    fun classify(
        files: List<HfTreeEntry>,
        repoId: String? = null,
    ): RunCompatibility {
        val paths = files.filter { it.isFile }.map { it.path }
        if (hasRunnableWeights(paths)) return RunCompatibility.RUNNABLE
        if (isAppleOnly(paths, repoId)) return RunCompatibility.IMPOSSIBLE
        return RunCompatibility.NEEDS_CONVERSION
    }

    private fun hasRunnableWeights(paths: List<String>): Boolean {
        if (paths.any { extensionOf(it) == ONNX_EXTENSION }) return true
        return hasRunnableGguf(paths)
    }

    // A GGUF is runnable only with either its `.gguf.json` manifest (GgmlTtsEngine) or the full native
    // multi-stage pipeline (CosyVoice2Engine); a bare single `.gguf` is NOT runnable (issue #108).
    private fun hasRunnableGguf(paths: List<String>): Boolean {
        if (paths.none { extensionOf(it) == GGUF_EXTENSION }) return false
        if (paths.any { it.endsWith(".$GGUF_EXTENSION.json") }) return true
        return NATIVE_GGUF_STAGES.all { stage ->
            paths.any { extensionOf(it) == GGUF_EXTENSION && it.lowercase().contains(stage) }
        }
    }

    // Apple-only: MLX ships as .safetensors (only the .mlx file or repo namespace proves it), and a
    // CoreML .mlpackage/.mlmodelc is a DIRECTORY whose inner files carry other extensions - so both
    // are matched as delimited path/name tokens rather than by a single file extension.
    private fun isAppleOnly(
        paths: List<String>,
        repoId: String?,
    ): Boolean = APPLE_TOKENS.any { token -> mentions(paths, repoId, token) }

    private fun mentions(
        paths: List<String>,
        repoId: String?,
        token: String,
    ): Boolean {
        val haystacks = paths + listOfNotNull(repoId)
        return haystacks.any { token in it.lowercase().split('-', '_', '.', '/', ' ') }
    }

    private fun extensionOf(path: String): String = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    private const val ONNX_EXTENSION = "onnx"
    private const val GGUF_EXTENSION = "gguf"
    private val APPLE_TOKENS = setOf("mlx", "coreml", "mlpackage", "mlmodel", "mlmodelc")

    // The stages the native GGUF pipeline (CosyVoice2Engine) requires all of - its file signature.
    private val NATIVE_GGUF_STAGES = listOf("llm", "flow", "hift", "voices")
}
