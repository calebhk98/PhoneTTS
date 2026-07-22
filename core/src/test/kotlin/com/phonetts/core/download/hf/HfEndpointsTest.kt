package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HfEndpointsTest {
    @Test
    fun searchUrlFiltersToTtsAndEncodesTheQuery() {
        val url = HfEndpoints.searchModelsUrl("kokoro tts", limit = 20)
        assertTrue(url.contains("pipeline_tag=text-to-speech"))
        assertTrue(url.contains("limit=20"))
        assertTrue(url.contains("search=kokoro%20tts"), "query should be URL-encoded: $url")
    }

    @Test
    fun blankQueryOmitsTheSearchParam() {
        val url = HfEndpoints.searchModelsUrl("   ", limit = 5)
        assertTrue(!url.contains("search="))
    }

    @Test
    fun defaultSkipIsOmittedFromTheFirstPagesUrl() {
        val url = HfEndpoints.searchModelsUrl("kokoro", limit = 20)
        assertTrue(!url.contains("skip="), "first page shouldn't carry a skip param: $url")
    }

    @Test
    fun nonZeroSkipPagesPastEarlierResults() {
        val url = HfEndpoints.searchModelsUrl("kokoro", limit = 20, skip = 20)
        assertTrue(url.contains("skip=20"), "expected a skip param for page 2: $url")
        assertTrue(url.contains("limit=20"))
    }

    @Test
    fun treeUrlIsRecursive() {
        assertEquals(
            "https://huggingface.co/api/models/hexgrad/Kokoro-82M/tree/main?recursive=true",
            HfEndpoints.treeUrl("hexgrad/Kokoro-82M", "main"),
        )
    }

    @Test
    fun treeUrlEncodesTheModelIdKeepingTheOwnerSlash() {
        assertEquals(
            "https://huggingface.co/api/models/owner/Model%20Name/tree/main?recursive=true",
            HfEndpoints.treeUrl("owner/Model Name", "main"),
        )
    }

    @Test
    fun modelPageUrlKeepsOwnerSlashAndEncodesSegments() {
        assertEquals(
            "https://huggingface.co/owner/Model%20Name",
            HfEndpoints.modelPageUrl("owner/Model Name"),
        )
    }

    @Test
    fun resolveUrlKeepsModelSlashAndEncodesPathSegments() {
        val url = HfEndpoints.resolveUrl("owner/Model Name", "main", "onnx/model file.onnx")
        assertEquals(
            "https://huggingface.co/owner/Model%20Name/resolve/main/onnx/model%20file.onnx",
            url,
        )
    }
}
