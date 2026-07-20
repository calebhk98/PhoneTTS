package com.phonetts.core.download.builtin

import com.phonetts.core.download.SafePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInModelTest {
    @Test
    fun downloadItemsFetchFromTheRepoPathButWriteToTheFlatLocalName() {
        val model =
            BuiltInModel(
                id = "m",
                displayName = "M",
                repoId = "owner/repo",
                approxSizeMb = 1,
                revision = "main",
                files = listOf(BuiltInFile(repoPath = "nested/dir/model.onnx", localName = "model.onnx")),
            )

        val item = model.downloadItems().single()

        assertEquals("model.onnx", item.relativePath) // flattened into the model folder
        assertTrue(item.url.endsWith("/owner/repo/resolve/main/nested/dir/model.onnx"), item.url)
    }

    @Test
    fun everyCuratedModelHasFilesAndSafeLocalPaths() {
        assertTrue(BuiltInCatalog.ALL.isNotEmpty())
        BuiltInCatalog.ALL.forEach { model ->
            assertTrue(model.files.isNotEmpty(), "${model.id} has no files")
            model.files.forEach { file ->
                assertTrue(SafePath.isSafe(file.localName), "${model.id}: unsafe local name ${file.localName}")
            }
        }
    }

    @Test
    fun curatedModelIdsAreUnique() {
        val ids = BuiltInCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate built-in model ids")
    }

    @Test
    fun oneModelPerEngineFamilyAndCosyVoiceIsRuntimeGated() {
        // Five curated models, one per engine family (Piper, KittenTTS, Kokoro, MeloTTS, CosyVoice3).
        assertEquals(5, BuiltInCatalog.ALL.size)
        // Only the native CosyVoice3 declares a required runtime; the four ONNX models run anywhere.
        val gated = BuiltInCatalog.ALL.filter { it.requiresRuntimeId != null }
        assertEquals(listOf("cosyvoice"), gated.map { it.requiresRuntimeId })
        assertEquals("cosyvoice3-0.5b", gated.single().id)
    }
}
