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
}
