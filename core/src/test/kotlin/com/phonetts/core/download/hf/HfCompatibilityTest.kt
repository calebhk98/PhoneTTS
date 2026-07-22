package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HfCompatibilityTest {
    @Test
    fun trueWhenAnOnnxFileIsPresent() {
        val files =
            listOf(
                HfTreeEntry(type = "file", path = "config.json", size = 100),
                HfTreeEntry(type = "file", path = "onnx/model.onnx", size = 8_000_000),
            )
        assertTrue(HfCompatibility.hasRunnableFiles(files))
    }

    @Test
    fun trueWhenACosyVoice3StyleGgufStackIsPresent() {
        val files =
            listOf(
                HfTreeEntry(type = "file", path = "cosyvoice3-llm-q4_k.gguf", size = 8_000_000),
                HfTreeEntry(type = "file", path = "cosyvoice3-flow-q8_0.gguf", size = 8_000_000),
            )
        assertTrue(HfCompatibility.hasRunnableFiles(files))
    }

    @Test
    fun falseForAPyTorchOnlyRepoWithNoOnnxOrGguf() {
        val files =
            listOf(
                HfTreeEntry(type = "file", path = "config.json", size = 100),
                HfTreeEntry(type = "file", path = "model.safetensors", size = 900_000_000),
                HfTreeEntry(type = "file", path = "pytorch_model.bin", size = 900_000_000),
            )
        assertFalse(HfCompatibility.hasRunnableFiles(files))
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
