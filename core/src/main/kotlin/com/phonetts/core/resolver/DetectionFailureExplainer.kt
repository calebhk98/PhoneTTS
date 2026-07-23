package com.phonetts.core.resolver

import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle

/** One companion-file category spec §6.2 names as what a self-describing bundle typically ships. */
private data class CompanionFileCategory(
    val label: String,
    val present: (ModelBundle) -> Boolean,
)

/** Which of the standard companion-file categories a bundle has, per [DetectionFailureExplainer]. */
data class CompanionFileFindings(
    val presentCategories: List<String>,
    val missingCategories: List<String>,
) {
    /** True when none of the standard companion-file categories were found on the bundle. */
    val isBareWeightsFile: Boolean get() = presentCategories.isEmpty()
}

/**
 * A weight-format category that, on its own, explains why no engine could run a bundle (issue #108):
 * the real cause is often not a missing companion file but the weight FORMAT itself. Derived only
 * from the bundle's own file extensions / name (never a hardcoded model-name list), and fail-closed:
 * an unrecognized shape yields no issue rather than a guess. The Apple and raw-PyTorch messages say
 * plainly that these cannot run on this app (wrong platform / offline conversion needed), never "not
 * yet" - there is no Android runtime for them and there never will be one in this app.
 */
private enum class WeightFormatIssue(
    val summary: String,
) {
    APPLE_MLX(
        "It's in Apple's MLX (Metal) format, which only runs on Apple hardware. It cannot run on this " +
            "app at all - this is not a 'not yet', there is no Android MLX runtime. Convert it to ONNX " +
            "on a computer first, or pick a different model.",
    ),
    APPLE_COREML(
        "It's in Apple's CoreML (Neural Engine) format, which only runs on Apple hardware. It cannot " +
            "run on this app at all - this is not a 'not yet'. Convert it to ONNX on a computer first, " +
            "or pick a different model.",
    ),
    RAW_PYTORCH(
        "It's a raw PyTorch / safetensors checkpoint. This app runs ONNX and native GGUF/LiteRT models, " +
            "and Android has no PyTorch runtime, so this cannot run here at all - this is not a 'not " +
            "yet'. Convert it to ONNX on a computer first, or pick a model that already ships ONNX.",
    ),
    NVIDIA_NEMO(
        "It's an NVIDIA NeMo (.nemo) package, which none of this app's runtimes can load. It would need " +
            "offline conversion to ONNX before it could run here.",
    ),
    TFLITE(
        "It's a TensorFlow Lite (.tflite) model, which this app does not load today. It would need " +
            "conversion to a supported format before it could run here.",
    ),
    BARE_GGUF(
        "It's a GGUF weights file with no companion '.gguf.json' manifest. The native GGML engine needs " +
            "that sidecar (naming the backend and sample rate) to run a GGUF voice, so without it the " +
            "app can't run this file.",
    ),
}

/**
 * Human-readable explanation of why no engine's [VoiceEngine.inspect] claimed a [ModelBundle]
 * (spec §6.2: `inspect()` fails closed rather than guessing, so an unclaimed bundle drops to the
 * user-pick fallback). Read-only and inert on its own: [explain] calls the same [VoiceEngine.inspect]
 * probe [com.phonetts.core.resolver.Resolver] already uses — a query method by the interface's own
 * contract — and never calls `load`, `forcedMatch`, or anything else that could change state. It
 * makes no [Resolver] calls and persists nothing.
 *
 * What it can and cannot know: no individual engine exposes *why* its `inspect()` declined a
 * bundle — that fingerprinting logic is private to each engine family (spec §5.1). So this class
 * reports only what is externally observable: which registered engines were asked and declined,
 * and which of the standard companion-file categories spec §6.2 lists (config.json, tokenizer,
 * phoneme map, voice/speaker table) are present or missing on the bundle itself.
 */
class DetectionFailureExplainer {
    fun explain(
        bundle: ModelBundle,
        engines: List<VoiceEngine>,
    ): DetectionFailureReport {
        val claimedByEngineIds = engines.filter { it.inspect(bundle) != null }.map { it.id }
        val findings = companionFileFindings(bundle)
        val formatIssue = weightFormatIssue(bundle)
        val summary = buildSummary(bundle, engines.map { it.id }, claimedByEngineIds, findings, formatIssue)
        return DetectionFailureReport(
            bundleId = bundle.id,
            checkedEngineIds = engines.map { it.id },
            claimedByEngineIds = claimedByEngineIds,
            presentCompanionFiles = findings.presentCategories,
            missingCompanionFiles = findings.missingCategories,
            isBareWeightsFile = findings.isBareWeightsFile,
            weightFormatIssue = formatIssue?.summary,
            summary = summary,
        )
    }

    private fun companionFileFindings(bundle: ModelBundle): CompanionFileFindings {
        val present = COMPANION_FILE_CATEGORIES.filter { it.present(bundle) }.map { it.label }
        val missing = COMPANION_FILE_CATEGORIES.filterNot { it.present(bundle) }.map { it.label }
        return CompanionFileFindings(present, missing)
    }

    // The weight-format cause, when the bundle's own files name one. Apple formats are checked first
    // (definitive "wrong platform"), then the distinct convertible packages, then raw PyTorch (its
    // safetensors overlap MLX, hence the earlier MLX check), then a GGUF missing its manifest.
    private fun weightFormatIssue(bundle: ModelBundle): WeightFormatIssue? {
        // A bundle that actually ships a runnable ONNX graph isn't blocked by its format, whatever the
        // folder is named - the failure is then about companion files, so let that path explain it.
        if (bundle.hasExtension(ONNX_EXTENSION)) return null
        // Apple formats first (definitive wrong-platform; their safetensors overlap raw PyTorch, hence
        // MLX before RAW_PYTORCH), then the convertible packages, then a GGUF missing its manifest.
        return when {
            bundle.mentions(MLX_TOKEN) -> WeightFormatIssue.APPLE_MLX
            COREML_TOKENS.any { bundle.mentions(it) } -> WeightFormatIssue.APPLE_COREML
            bundle.hasExtension(NEMO_EXTENSION) -> WeightFormatIssue.NVIDIA_NEMO
            bundle.hasExtension(TFLITE_EXTENSION) -> WeightFormatIssue.TFLITE
            bundle.looksLikeRawPyTorch() -> WeightFormatIssue.RAW_PYTORCH
            bundle.isBareGguf() -> WeightFormatIssue.BARE_GGUF
            else -> null
        }
    }

    private fun buildSummary(
        bundle: ModelBundle,
        checkedEngineIds: List<String>,
        claimedByEngineIds: List<String>,
        findings: CompanionFileFindings,
        formatIssue: WeightFormatIssue?,
    ): String {
        if (claimedByEngineIds.isNotEmpty()) {
            return "Good news - '${bundle.id}' was actually recognized by: ${claimedByEngineIds.joinToString()}. " +
                "It should already be usable; this wasn't really a failure."
        }
        if (checkedEngineIds.isEmpty()) {
            return "There are no engines installed yet to check '${bundle.id}' against, so nothing could " +
                "recognize it."
        }
        if (formatIssue != null) {
            return "None of the ${checkedEngineIds.size} available engine(s) recognized '${bundle.id}'. " +
                formatIssue.summary
        }
        if (findings.isBareWeightsFile) {
            return "None of the ${checkedEngineIds.size} available engine(s) recognized '${bundle.id}'. " +
                "It looks like a bare weights file with no extra files describing how to run it " +
                "($COMPANION_FILE_LABEL_LIST are all missing), so the app can't safely guess which " +
                "model this is. Try a download that includes those files, or pick an engine for it " +
                "yourself below."
        }
        return "None of the ${checkedEngineIds.size} available engine(s) recognized '${bundle.id}'. " +
            "It's missing these usual files: ${findings.missingCategories.joinToString()} — without " +
            "them the app can't tell which model this is with confidence. Try a download that " +
            "includes those files, or pick an engine for it yourself below."
    }

    companion object {
        private val COMPANION_FILE_CATEGORIES =
            listOf(
                CompanionFileCategory("config.json") { it.hasFile("config.json") },
                CompanionFileCategory("tokenizer") { bundle ->
                    bundle.fileNames.any {
                        it.contains("tokenizer", ignoreCase = true) || it.contains("vocab", ignoreCase = true)
                    }
                },
                CompanionFileCategory("phoneme map") { bundle ->
                    bundle.fileNames.any { it.contains("phoneme", ignoreCase = true) }
                },
                CompanionFileCategory("voice/speaker table") { bundle ->
                    bundle.fileNames.any {
                        it.contains("voice", ignoreCase = true) || it.contains("speaker", ignoreCase = true)
                    }
                },
            )
        private val COMPANION_FILE_LABEL_LIST = COMPANION_FILE_CATEGORIES.joinToString { it.label }
    }
}

// Format signals, derived only from file extensions / bundle name (never a hardcoded model-name list).
private const val ONNX_EXTENSION = "onnx"
private const val GGUF_EXTENSION = "gguf"
private const val NEMO_EXTENSION = "nemo"
private const val TFLITE_EXTENSION = "tflite"
private const val MLX_TOKEN = "mlx"

// CoreML .mlpackage/.mlmodelc are DIRECTORIES whose inner files carry other extensions, so the
// format is recognized as a delimited path/name token rather than by a single file extension.
private val COREML_TOKENS = setOf("coreml", "mlpackage", "mlmodel", "mlmodelc")

// Raw-PyTorch original weights (no on-device torch runtime; convert to ONNX first).
private val TORCH_WEIGHT_EXTENSIONS = setOf("safetensors", "pth", "pt", "ckpt", "bin")

// The stages the native GGUF pipeline (CosyVoice2Engine) requires all of - its file signature.
private val NATIVE_GGUF_STAGES = listOf("llm", "flow", "hift", "voices")

private fun extensionOf(name: String): String = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

private fun ModelBundle.hasExtension(extension: String): Boolean = fileNames.any { extensionOf(it) == extension }

private fun ModelBundle.hasAnyExtension(extensions: Set<String>): Boolean =
    fileNames.any { extensionOf(it) in extensions }

/** True when [token] appears as a whole delimited word in any file name or the bundle id. */
private fun ModelBundle.mentions(token: String): Boolean {
    val haystacks = fileNames + id
    return haystacks.any { token in it.lowercase().split('-', '_', '.', '/', ' ') }
}

/** A weight-file extension with no ONNX packaging - the raw-checkpoint shape this app can't run. */
private fun ModelBundle.looksLikeRawPyTorch(): Boolean {
    if (hasExtension(ONNX_EXTENSION)) return false
    return hasAnyExtension(TORCH_WEIGHT_EXTENSIONS)
}

/** A .gguf present but neither a `.gguf.json` sidecar nor the full native stage stack alongside it. */
private fun ModelBundle.isBareGguf(): Boolean {
    if (!hasExtension(GGUF_EXTENSION)) return false
    if (fileNames.any { it.endsWith(".$GGUF_EXTENSION.json") }) return false
    return !hasNativeGgufStack()
}

private fun ModelBundle.hasNativeGgufStack(): Boolean =
    NATIVE_GGUF_STAGES.all { stage ->
        fileNames.any { extensionOf(it) == GGUF_EXTENSION && it.lowercase().contains(stage) }
    }

/** The result of [DetectionFailureExplainer.explain] — a narration, not a new detection decision. */
data class DetectionFailureReport(
    val bundleId: String,
    val checkedEngineIds: List<String>,
    val claimedByEngineIds: List<String>,
    val presentCompanionFiles: List<String>,
    val missingCompanionFiles: List<String>,
    val isBareWeightsFile: Boolean,
    val summary: String,
    // The weight-format cause of the failure, if the bundle's files name one; null when the format
    // is fine and the failure is about missing companion files instead (issue #108).
    val weightFormatIssue: String? = null,
)
