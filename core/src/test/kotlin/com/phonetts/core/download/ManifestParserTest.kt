package com.phonetts.core.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManifestParserTest {
    @Test
    fun parsesAHandWrittenManifest() {
        val json =
            """
            {
              "models": [
                {
                  "modelId": "piper-en-amy",
                  "displayName": "Piper - Amy (en)",
                  "engineId": "piper",
                  "files": [
                    { "name": "amy.onnx", "url": "https://example/amy.onnx", "sha256": "abcd", "sizeBytes": 1234 }
                  ]
                }
              ]
            }
            """.trimIndent()

        val manifest = ManifestParser.parse(json)

        assertEquals(1, manifest.models.size)
        val model = manifest.models.first()
        assertEquals("piper-en-amy", model.modelId)
        assertEquals("piper", model.engineId)
        assertEquals(1, model.files.size)
        assertEquals("amy.onnx", model.files.first().name)
        assertEquals(1234L, model.files.first().sizeBytes)
    }

    @Test
    fun omittedEngineHintDefaultsToNull() {
        val json = """{ "models": [ { "modelId": "mystery", "displayName": "Mystery" } ] }"""
        val model = ManifestParser.parse(json).models.first()
        assertNull(model.engineId)
        assertEquals(emptyList(), model.files)
    }

    @Test
    fun roundTripsThroughEncodeAndParse() {
        val original =
            ModelManifest(
                models =
                    listOf(
                        ManifestModel(
                            modelId = "kokoro-82m",
                            displayName = "Kokoro-82M",
                            files = listOf(ManifestFile("kokoro.onnx", "https://example/k.onnx", "DEADBEEF")),
                        ),
                    ),
            )
        assertEquals(original, ManifestParser.parse(ManifestParser.encode(original)))
    }

    @Test
    fun ignoresUnknownForwardCompatibleKeys() {
        val json = """{ "schemaVersion": 2, "models": [], "notes": "future field" }"""
        assertEquals(emptyList(), ManifestParser.parse(json).models)
    }
}
