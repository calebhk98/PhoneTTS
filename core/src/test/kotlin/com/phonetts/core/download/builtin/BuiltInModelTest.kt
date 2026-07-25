package com.phonetts.core.download.builtin

import com.phonetts.core.download.SafePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun downloadItemsCarryThePinnedHashWhenTheFileHasOne() {
        val model =
            BuiltInModel(
                id = "m",
                displayName = "M",
                repoId = "owner/repo",
                approxSizeMb = 1,
                files = listOf(BuiltInFile(repoPath = "config.json", localName = "config.json", sha256 = "deadbeef")),
            )

        assertEquals("deadbeef", model.downloadItems().single().expectedSha256)
    }

    @Test
    fun downloadItemsLeaveTheHashNullRatherThanFabricatingOne() {
        val model =
            BuiltInModel(
                id = "m",
                displayName = "M",
                repoId = "owner/repo",
                approxSizeMb = 1,
                files = listOf(BuiltInFile(repoPath = "unverifiable.bin", localName = "unverifiable.bin")),
            )

        assertNull(model.downloadItems().single().expectedSha256)
    }

    @Test
    fun everyPinnedCuratedHashIsSixtyFourLowercaseHexChars() {
        val hex = Regex("[0-9a-f]{64}")
        BuiltInCatalog.ALL.forEach { model ->
            model.files.forEach { file ->
                val hash = file.sha256 ?: return@forEach
                assertTrue(hex.matches(hash), "${model.id}/${file.localName} has a malformed sha256: $hash")
            }
        }
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
