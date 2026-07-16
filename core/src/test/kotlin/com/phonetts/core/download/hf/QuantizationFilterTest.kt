package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuantizationClassifierTest {
    @Test
    fun labelsAnUnsuffixedWeightFileAsFp32() {
        assertEquals(QuantizationVariant.FP32, QuantizationClassifier.classify("onnx/model.onnx"))
    }

    @Test
    fun labelsExplicitFp16Fp32Q8Q4AndInt8Suffixes() {
        assertEquals(QuantizationVariant.FP16, QuantizationClassifier.classify("model_fp16.onnx"))
        assertEquals(QuantizationVariant.FP32, QuantizationClassifier.classify("model_fp32.onnx"))
        assertEquals(QuantizationVariant.Q8, QuantizationClassifier.classify("model_q8.onnx"))
        assertEquals(QuantizationVariant.Q4, QuantizationClassifier.classify("model_q4.onnx"))
        assertEquals(QuantizationVariant.INT8, QuantizationClassifier.classify("model.int8.onnx"))
        assertEquals(QuantizationVariant.INT8, QuantizationClassifier.classify("model_uint8.onnx"))
    }

    @Test
    fun labelsAGenericQuantizedSuffixAsInt8() {
        assertEquals(QuantizationVariant.INT8, QuantizationClassifier.classify("model_quantized.onnx"))
    }

    @Test
    fun aMoreSpecificTokenWinsOverAGenericOneInAMixedPrecisionFilename() {
        // real-world naming: q8 weights + fp16 activations - the enum has no mixed bucket, so the
        // more specific "q8" token (checked before fp16) wins.
        assertEquals(QuantizationVariant.Q8, QuantizationClassifier.classify("model_q8f16.onnx"))
        assertEquals(QuantizationVariant.Q4, QuantizationClassifier.classify("model_q4f16.onnx"))
    }

    @Test
    fun nonWeightFilesAreUnknownRatherThanGuessedAsFp32() {
        assertEquals(QuantizationVariant.UNKNOWN, QuantizationClassifier.classify("config.json"))
        assertEquals(QuantizationVariant.UNKNOWN, QuantizationClassifier.classify("tokenizer.json"))
    }

    @Test
    fun anUnrecognizedWeightSuffixIsUnknownNotGuessed() {
        assertEquals(QuantizationVariant.UNKNOWN, QuantizationClassifier.classify("model_experimental.onnx"))
    }

    @Test
    fun classificationIsCaseInsensitiveAndFamilyAgnostic() {
        assertEquals(QuantizationVariant.FP16, QuantizationClassifier.classify("VOICES_FP16.ONNX"))
        assertEquals(QuantizationVariant.Q4, QuantizationClassifier.classify("some/nested/path/weights_Q4.bin"))
    }
}

class QuantizationFilterTest {
    // A realistic multi-precision ONNX repo layout, per the ticket's examples.
    private val files =
        listOf(
            HfTreeEntry(type = "directory", path = "onnx"),
            HfTreeEntry(type = "file", path = "config.json", size = 1_200),
            HfTreeEntry(type = "file", path = "tokenizer.json", size = 3_400),
            HfTreeEntry(type = "file", path = "onnx/model.onnx", size = 80_000_000),
            HfTreeEntry(type = "file", path = "onnx/model_fp16.onnx", size = 40_000_000),
            HfTreeEntry(type = "file", path = "onnx/model_q8.onnx", size = 20_000_000),
            HfTreeEntry(type = "file", path = "onnx/model_q4.onnx", size = 10_000_000),
            HfTreeEntry(type = "file", path = "onnx/model.int8.onnx", size = 20_500_000),
            HfTreeEntry(type = "file", path = "onnx/model_quantized.onnx", size = 20_600_000),
        )

    @Test
    fun availableVariantsCollectsEveryDistinctPrecisionAmongWeightFilesOnly() {
        val variants = QuantizationFilter.availableVariants(files)

        assertEquals(
            setOf(
                QuantizationVariant.FP32,
                QuantizationVariant.FP16,
                QuantizationVariant.Q8,
                QuantizationVariant.Q4,
                QuantizationVariant.INT8,
            ),
            variants,
        )
    }

    @Test
    fun filesForVariantReturnsOnlyThatPrecisionsWeightsPlusSharedFiles() {
        val picked = QuantizationFilter.filesForVariant(files, QuantizationVariant.FP16)

        assertEquals(
            setOf("config.json", "tokenizer.json", "onnx/model_fp16.onnx"),
            picked.map { it.path }.toSet(),
        )
    }

    @Test
    fun filesForVariantExcludesDirectoriesAndOtherPrecisions() {
        val picked = QuantizationFilter.filesForVariant(files, QuantizationVariant.Q4)

        assertTrue(picked.none { it.type == "directory" })
        assertTrue(picked.none { it.path.contains("q8") || it.path.contains("fp16") })
        assertTrue(picked.any { it.path == "onnx/model_q4.onnx" })
    }

    @Test
    fun requestingAnAbsentVariantYieldsOnlyTheSharedFiles() {
        val picked = QuantizationFilter.filesForVariant(files, QuantizationVariant.UNKNOWN)

        assertEquals(setOf("config.json", "tokenizer.json"), picked.map { it.path }.toSet())
    }
}

class HfQuantizedDownloadPlanTest {
    private val files =
        listOf(
            HfTreeEntry(type = "file", path = "config.json", size = 1_200),
            HfTreeEntry(type = "file", path = "onnx/model_fp16.onnx", size = 40_000_000),
            HfTreeEntry(type = "file", path = "onnx/model_q4.onnx", size = 10_000_000),
        )

    @Test
    fun buildsResolveUrlsScopedToTheChosenVariant() {
        val items = HfQuantizedDownloadPlan.forVariant("hexgrad/Kokoro-82M", files, QuantizationVariant.Q4)

        assertEquals(2, items.size)
        assertTrue(items.any { it.relativePath == "config.json" })
        assertTrue(items.any { it.relativePath == "onnx/model_q4.onnx" })
        assertTrue(items.none { it.relativePath == "onnx/model_fp16.onnx" })
        val q4Url = items.first { it.relativePath == "onnx/model_q4.onnx" }.url
        assertTrue(q4Url.endsWith("/resolve/main/onnx/model_q4.onnx"))
    }
}
