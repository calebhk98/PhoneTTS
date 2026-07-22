package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HfDownloadPlanTest {
    private val tree =
        listOf(
            HfTreeEntry(type = "directory", path = "onnx"),
            HfTreeEntry(type = "file", path = "config.json", size = 1200),
            HfTreeEntry(type = "file", path = "onnx/model.onnx", size = 8_400_000),
        )

    @Test
    fun buildsDownloadItemsForFilesOnlyWithResolveUrls() {
        val items = HfDownloadPlan.forFiles("hexgrad/Kokoro-82M", tree)

        assertEquals(2, items.size, "directories must be excluded")
        val config = items.first { it.relativePath == "config.json" }
        assertEquals("https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/config.json", config.url)
        assertEquals(1200L, config.sizeBytes)
        assertTrue(items.any { it.url.endsWith("/resolve/main/onnx/model.onnx") })
    }

    @Test
    fun rejectsATraversalPathInTheRepoFileList() {
        val hostile = listOf(HfTreeEntry(type = "file", path = "../../evil.bin", size = 10))
        assertFailsWith<IllegalArgumentException> { HfDownloadPlan.forFiles("owner/repo", hostile) }
    }

    @Test
    fun excludesVcsAndRepoMetadataFilesButKeepsRealModelFiles() {
        val repo =
            listOf(
                HfTreeEntry(type = "file", path = ".gitattributes", size = 500),
                HfTreeEntry(type = "file", path = ".gitignore", size = 20),
                HfTreeEntry(type = "file", path = "subdir/.gitattributes", size = 500),
                HfTreeEntry(type = "file", path = "README.md", size = 2000),
                HfTreeEntry(type = "file", path = "config.json", size = 1200),
                HfTreeEntry(type = "file", path = "model.safetensors", size = 900_000_000),
                HfTreeEntry(type = "file", path = "pytorch_model.bin", size = 900_000_000),
                HfTreeEntry(type = "file", path = "weights.pt", size = 500_000),
                HfTreeEntry(type = "file", path = "model.gguf", size = 500_000),
            )

        val items = HfDownloadPlan.forFiles("sesame/csm-1b", repo)

        val paths = items.map { it.relativePath }.toSet()
        assertTrue(".gitattributes" !in paths, "root .gitattributes must be excluded")
        assertTrue(".gitignore" !in paths, ".gitignore must be excluded")
        assertTrue("subdir/.gitattributes" !in paths, "nested .gitattributes must be excluded")
        assertEquals(6, items.size, "only the 6 real model/config/readme files should remain")
        assertTrue("model.safetensors" in paths, "safetensors weights must be kept for future engine support")
        assertTrue("pytorch_model.bin" in paths, "pytorch .bin weights must be kept")
        assertTrue("weights.pt" in paths, ".pt weights must be kept")
        assertTrue("model.gguf" in paths, ".gguf weights must be kept")
        assertTrue("config.json" in paths, "config.json must be kept")
    }
}
