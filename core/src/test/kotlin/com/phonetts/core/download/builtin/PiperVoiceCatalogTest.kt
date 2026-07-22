package com.phonetts.core.download.builtin

import com.phonetts.core.download.SafePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PiperVoiceCatalog] is generated data (from rhasspy/piper-voices' `voices.json`), so these
 * tests guard the shape every entry must have to actually work: exactly one `.onnx` +
 * `.onnx.json` pair per voice, named so PiperEngine's fail-closed `inspect()` (which looks for
 * `<onnxFile>.json` sitting next to the `.onnx`) recognizes it, and no duplicated/unsafe
 * identifiers — the same guarantees [BuiltInModelTest] checks for the small curated
 * `BuiltInCatalog.ALL` list, extended to the much larger Piper set.
 */
class PiperVoiceCatalogTest {
    @Test
    fun coversSubstantiallyMoreThanTheOldSingleCuratedVoice() {
        // Was 1 (BuiltInCatalog.PIPER_LESSAC only); the full rhasspy/piper-voices index is ~166
        // voices across 50+ languages. Assert "many", not an exact count, so a routine upstream
        // refresh doesn't need a test edit.
        assertTrue(PiperVoiceCatalog.ALL.size > 100, "expected many Piper voices, got ${PiperVoiceCatalog.ALL.size}")
    }

    @Test
    fun everyVoiceIsFromThePiperVoicesRepoWithAPiperPrefixedId() {
        PiperVoiceCatalog.ALL.forEach { voice ->
            assertEquals("rhasspy/piper-voices", voice.repoId, voice.id)
            assertTrue(voice.id.startsWith("piper-"), "unexpected id shape: ${voice.id}")
            assertTrue(voice.approxSizeMb > 0, "${voice.id} has a non-positive size estimate")
        }
    }

    @Test
    fun idsAndDisplayNamesAreUnique() {
        val ids = PiperVoiceCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate Piper voice ids")

        val names = PiperVoiceCatalog.ALL.map { it.displayName }
        assertEquals(names.size, names.toSet().size, "duplicate Piper voice display names")
    }

    @Test
    fun everyVoiceHasExactlyOneOnnxAndOneMatchingSidecarWithSafeLocalNames() {
        PiperVoiceCatalog.ALL.forEach { voice ->
            assertEquals(2, voice.files.size, "${voice.id}: expected exactly [onnx, sidecar]")

            val onnx = voice.files.single { it.localName.endsWith(".onnx") }
            val sidecar = voice.files.single { it.localName.endsWith(".onnx.json") }

            // What PiperEngine.inspect()/validVoiceEntry() actually requires: a sidecar file
            // literally named "<onnxLocalName>.json" next to the graph (spec rule 4, fail-closed
            // — a mismatched pair here would silently make the voice unresolvable).
            assertEquals(
                "${onnx.localName}.json",
                sidecar.localName,
                "${voice.id}: sidecar local name doesn't match PiperEngine's <onnx>.json expectation",
            )

            assertTrue(SafePath.isSafe(onnx.localName), "${voice.id}: unsafe onnx local name")
            assertTrue(SafePath.isSafe(sidecar.localName), "${voice.id}: unsafe sidecar local name")
            assertTrue(SafePath.isSafe(onnx.repoPath), "${voice.id}: unsafe onnx repo path")
            assertTrue(SafePath.isSafe(sidecar.repoPath), "${voice.id}: unsafe sidecar repo path")
        }
    }

    @Test
    fun eachVoiceDownloadsOnlyItsOwnTwoFilesNeverTheWholeRepo() {
        PiperVoiceCatalog.ALL.forEach { voice ->
            val items = voice.downloadItems()
            assertEquals(2, items.size, "${voice.id} should fetch exactly its own onnx + sidecar")
            items.forEach { item -> assertTrue(item.url.contains("/rhasspy/piper-voices/"), item.url) }
        }
    }

    @Test
    fun builtInCatalogExposesTheFullPiperListSeparatelyFromTheSmallRecommendedGrid() {
        // BuiltInCatalog.ALL (the one-tap "recommended" grid) stays deliberately small — one Piper
        // voice — while PIPER_VOICES carries the full browsable set. They must not be conflated.
        assertTrue(BuiltInCatalog.PIPER_VOICES.size == PiperVoiceCatalog.ALL.size)
        assertTrue(BuiltInCatalog.ALL.count { it.id.startsWith("piper-") } == 1)
        assertTrue(BuiltInCatalog.PIPER_LESSAC.id in BuiltInCatalog.PIPER_VOICES.map { it.id })
    }
}
