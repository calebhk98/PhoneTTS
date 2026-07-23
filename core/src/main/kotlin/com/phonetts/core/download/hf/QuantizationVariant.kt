package com.phonetts.core.download.hf

/**
 * A weight-precision label. Many Hugging Face repos ship the same model at several precisions
 * (e.g. `model.onnx`, `model_fp16.onnx`, `model_q8.onnx`, `model.int8.onnx`,
 * `model_quantized.onnx`) so a low-memory device can pick a smaller file. [UNKNOWN] is the
 * fail-closed bucket for a weight file whose filename carries no precision signal this
 * classifier recognizes - it is still surfaced (never silently dropped), just not assumed to be
 * any particular precision.
 */
enum class QuantizationVariant {
    FP32,
    FP16,
    INT8,
    Q8,
    Q4,
    UNKNOWN,
}

/**
 * Labels a repo file's precision purely from its filename - no engine or model-family knowledge,
 * so it stays SSOT-clean (spec rule 1: no model fact may be hardcoded outside the
 * resolver/descriptor layer, and this classifier is not part of that layer - it only reads
 * generic naming conventions any repo might use).
 */
object QuantizationClassifier {
    /** Extensions this classifier treats as model weights, worth labeling with a precision. */
    private val WEIGHT_EXTENSIONS =
        setOf("onnx", "bin", "safetensors", "pt", "pth", "gguf", "ort", "tflite", "npz", "ckpt")

    // Checked most-specific-first: a filename can contain more than one token (e.g. the common
    // "model_q8f16.onnx" mixed-precision naming), so the first matching entry wins.
    private val TOKEN_VARIANTS: List<Pair<List<String>, QuantizationVariant>> =
        listOf(
            listOf("q4") to QuantizationVariant.Q4,
            listOf("q8") to QuantizationVariant.Q8,
            listOf("int8", "uint8", "int_8", "quantized") to QuantizationVariant.INT8,
            listOf("fp16", "float16") to QuantizationVariant.FP16,
            listOf("fp32", "float32") to QuantizationVariant.FP32,
        )

    // A stem with none of these separators (e.g. "model") carries no qualifier at all, so it's
    // conventionally the unlabeled full-precision baseline. A stem that *does* have a separator
    // but none of its tokens matched (e.g. "model_experimental") is genuinely ambiguous - fail
    // closed to UNKNOWN rather than guessing it's the baseline.
    private val STEM_SEPARATORS = charArrayOf('_', '-', '.')

    /**
     * Labels [path]'s precision. [QuantizationVariant.UNKNOWN] is returned fail-closed for
     * anything that isn't a recognized weight extension (e.g. `config.json` - not a
     * precision-specific weight at all; see [QuantizationFilter], which treats those as shared)
     * or a weight file whose qualifier doesn't match a known token.
     */
    fun classify(path: String): QuantizationVariant {
        if (!isWeightFile(path)) return QuantizationVariant.UNKNOWN
        val stem = stem(path).lowercase()
        val matched = TOKEN_VARIANTS.firstOrNull { (tokens, _) -> tokens.any { stem.contains(it) } }?.second
        return matched ?: if (isBareStem(stem)) QuantizationVariant.FP32 else QuantizationVariant.UNKNOWN
    }

    /** True if [path]'s extension is a recognized model-weight format. */
    fun isWeightFile(path: String): Boolean = extension(path) in WEIGHT_EXTENSIONS

    /** True if [stem] has no separator at all - an unqualified name like "model". */
    private fun isBareStem(stem: String): Boolean = stem.none { it in STEM_SEPARATORS }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    /** The filename without its final extension, e.g. "model.int8.onnx" -> "model.int8". */
    private fun stem(path: String): String {
        val name = fileName(path)
        val ext = extension(path)
        return if (ext.isEmpty()) name else name.removeSuffix(".$ext")
    }

    private fun extension(path: String): String =
        fileName(path).substringAfterLast('.', missingDelimiterValue = "").lowercase()
}
