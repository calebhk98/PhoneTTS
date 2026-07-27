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
    fun onlyTheNativeModelIsRuntimeGated() {
        // Deliberately NO count assertion (CLAUDE.md: no magic counts in tests). ALL is a
        // hand-curated list with no second source of truth to derive a number from, so asserting
        // its length only ever restates the diff - it never catches a defect. What DOES matter is
        // the gating invariant below, which decides whether a one-tap download can land a model
        // this build cannot run.
        assertTrue(BuiltInCatalog.ALL.isNotEmpty(), "the curated catalog must not be empty")
        // Only the native CosyVoice3 declares a required runtime; every other curated model is ONNX
        // and runs in any build.
        val gated = BuiltInCatalog.ALL.filter { it.requiresRuntimeId != null }
        assertEquals(listOf("cosyvoice"), gated.map { it.requiresRuntimeId })
        assertEquals("cosyvoice3-0.5b", gated.single().id)
    }
}
