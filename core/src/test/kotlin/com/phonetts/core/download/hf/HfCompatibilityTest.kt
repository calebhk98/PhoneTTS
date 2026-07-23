package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HfCompatibilityTest {
    private fun file(path: String) = HfTreeEntry(type = "file", path = path, size = 8_000_000)

    @Test
    fun trueWhenAnOnnxFileIsPresent() {
        val files =
            listOf(
                HfTreeEntry(type = "file", path = "config.json", size = 100),
                HfTreeEntry(type = "file", path = "onnx/model.onnx", size = 8_000_000),
            )
        assertTrue(HfCompatibility.hasRunnableFiles(files))
        assertEquals(RunCompatibility.RUNNABLE, HfCompatibility.classify(files))
    }

    @Test
    fun trueWhenTheFullNativeGgufStackIsPresent() {
        // The native pipeline (CosyVoice2Engine) requires all four stages - that is its signature,
        // matched by the architectural stage keywords, not the model name.
        val files =
            listOf(
                file("cosyvoice3-llm-q4_k.gguf"),
                file("cosyvoice3-flow-q8_0.gguf"),
                file("cosyvoice3-hift-f16.gguf"),
                file("cosyvoice3-voices.gguf"),
            )
        assertTrue(HfCompatibility.hasRunnableFiles(files))
        assertEquals(RunCompatibility.RUNNABLE, HfCompatibility.classify(files))
    }

    @Test
    fun trueWhenAGgufHasItsManifestSidecar() {
        val files =
            listOf(
                file("en_US-lessac-medium.gguf"),
                HfTreeEntry(type = "file", path = "en_US-lessac-medium.gguf.json", size = 400),
            )
        assertTrue(HfCompatibility.hasRunnableFiles(files))
        assertEquals(RunCompatibility.RUNNABLE, HfCompatibility.classify(files))
    }

    @Test
    fun bareSingleGgufWithoutSidecarIsNotRunnable() {
        // An Orpheus/Qwen-style single .gguf looked "supported" under the old has-.gguf heuristic,
        // but the ggml engine refuses it without a .gguf.json manifest (issue #108).
        val files = listOf(file("orpheus-3b-0.1-ft-q4_k_m.gguf"))
        assertFalse(HfCompatibility.hasRunnableFiles(files))
        assertEquals(RunCompatibility.NEEDS_CONVERSION, HfCompatibility.classify(files))
    }

    @Test
    fun rawPyTorchRepoNeedsConversion() {
        val files =
            listOf(
                HfTreeEntry(type = "file", path = "config.json", size = 100),
                file("model.safetensors"),
                file("pytorch_model.bin"),
            )
        assertFalse(HfCompatibility.hasRunnableFiles(files))
        assertEquals(RunCompatibility.NEEDS_CONVERSION, HfCompatibility.classify(files))
    }

    @Test
    fun nemoAndTfliteNeedConversion() {
        assertEquals(RunCompatibility.NEEDS_CONVERSION, HfCompatibility.classify(listOf(file("model.nemo"))))
        assertEquals(RunCompatibility.NEEDS_CONVERSION, HfCompatibility.classify(listOf(file("model.tflite"))))
    }

    @Test
    fun coreMlPackageIsImpossible() {
        val files =
            listOf(
                HfTreeEntry(type = "directory", path = "Model.mlpackage"),
                file("Model.mlpackage/weights.bin"),
            )
        assertFalse(HfCompatibility.hasRunnableFiles(files))
        assertEquals(RunCompatibility.IMPOSSIBLE, HfCompatibility.classify(files))
    }

    @Test
    fun mlxWeightsAreImpossibleWhenNamedSo() {
        // MLX ships safetensors, so the .safetensors alone reads as convertible; the mlx signal (an
        // explicit .mlx file, or the repo namespace) is what proves it is Apple-only.
        assertEquals(RunCompatibility.IMPOSSIBLE, HfCompatibility.classify(listOf(file("weights.mlx"))))
        assertEquals(
            RunCompatibility.IMPOSSIBLE,
            HfCompatibility.classify(listOf(file("model.safetensors")), repoId = "mlx-community/Kokoro-82M-bf16"),
        )
    }

    @Test
    fun onnxWinsOverAnAppleNamedRepo() {
        val files = listOf(file("model.onnx"), file("model.safetensors"))
        assertEquals(
            RunCompatibility.RUNNABLE,
            HfCompatibility.classify(files, repoId = "mlx-community/whatever-ONNX"),
        )
    }

    @Test
    fun ignoresDirectoriesAndIsCaseInsensitive() {
        val files =
            listOf(
                HfTreeEntry(type = "directory", path = "ONNX"),
                HfTreeEntry(type = "file", path = "model.ONNX", size = 8_000_000),
            )
        assertTrue(HfCompatibility.hasRunnableFiles(files))
    }
}
