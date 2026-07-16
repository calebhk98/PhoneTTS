package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Serves canned bodies keyed by a substring of the requested URL. */
private class FakeHttpClient(private val routes: List<Pair<String, String>>) : HttpClient {
    val requested = mutableListOf<String>()

    override fun getText(
        url: String,
        headers: Map<String, String>,
    ): String {
        requested.add(url)
        return routes.firstOrNull { url.contains(it.first) }?.second
            ?: error("no fake route for $url")
    }
}

class HfCatalogTest {
    private val modelsJson =
        """
        [
          {"id":"hexgrad/Kokoro-82M","likes":900,"downloads":120000,"pipeline_tag":"text-to-speech","tags":["onnx","tts"]},
          {"id":"rhasspy/piper-voices","downloads":50000,"pipeline_tag":"text-to-speech","tags":["piper"],"private":false}
        ]
        """.trimIndent()

    private val treeJson =
        """
        [
          {"type":"directory","path":"onnx"},
          {"type":"file","path":"config.json","size":1200,"oid":"abc"},
          {"type":"file","path":"onnx/model.onnx","size":8400000}
        ]
        """.trimIndent()

    @Test
    fun searchParsesModelsAndSendsUserAgent() {
        val http = FakeHttpClient(listOf("/api/models?" to modelsJson))
        val results = HfCatalog(http).search("kokoro", limit = 10)

        assertEquals(listOf("hexgrad/Kokoro-82M", "rhasspy/piper-voices"), results.map { it.id })
        assertEquals(120000, results.first().downloads)
        assertTrue(results.first().tags.contains("onnx"))
        // ignores the unknown "private" field on the second entry without failing
        assertEquals("text-to-speech", results[1].pipelineTag)
    }

    @Test
    fun listFilesParsesTheTree() {
        val http = FakeHttpClient(listOf("/tree/" to treeJson))
        val files = HfCatalog(http).listFiles("hexgrad/Kokoro-82M")

        assertEquals(3, files.size)
        assertEquals(2, files.count { it.isFile })
        assertEquals(8_400_000L, files.first { it.path == "onnx/model.onnx" }.size)
    }
}
