package com.phonetts.core.model

import com.phonetts.core.registry.ModelUsage
import com.phonetts.core.registry.UnresolvedModelUsage
import com.phonetts.core.testing.testDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelListExportTest {
    @Test
    fun `header carries the count and grand total across resolved and unresolved bundles`() {
        val resolved =
            ExportableModel.from(
                usage = ModelUsage(testDescriptor("m1", "eng1"), sizeBytes = 1_048_576L),
                facts = null,
            )
        val unresolved = UnresolvedModelUsage("owner_name", sizeBytes = 1_048_576L, reason = "unrecognized")

        val text = ModelListExport.build(listOf(resolved), listOf(unresolved))

        assertEquals("Downloaded models (2) — 2.0 MB total", text.lines().first())
    }

    @Test
    fun `a resolved model with no facts yet still prints name origin and size only`() {
        val usage = ModelUsage(testDescriptor("m1", "eng1", origin = Origin.SIDELOADED), sizeBytes = 2048L)

        val text = ModelListExport.build(listOf(ExportableModel.from(usage, facts = null)), emptyList())

        assertEquals("• Model m1 — Sideloaded · 2.0 KB", text.lines()[1])
    }

    @Test
    fun `a resolved model with full facts prints ram params rtf and its hf link`() {
        val usage = ModelUsage(testDescriptor("kitten-nano", "eng1"), sizeBytes = 44_040_192L)
        val facts =
            ManageModelFacts(
                hfRepoId = "KittenML/kitten-tts-nano-0.1",
                paramCount = 15_000_000L,
                peakRamBytes = 262_144_000L,
                ramIsMeasured = false,
                realtimeMultiple = 3.2,
                realtimeIsMeasured = true,
            )

        val text = ModelListExport.build(listOf(ExportableModel.from(usage, facts)), emptyList())
        val line = text.lines()[1]

        assertTrue(line.startsWith("• Model kitten-nano — Built-in · 42.0 MB"))
        assertTrue(line.contains("Est. RAM ~250.0 MB"))
        assertTrue(line.contains("~15M params"))
        assertTrue(line.contains("~3.2x real-time (measured)"))
        assertTrue(line.contains("https://huggingface.co/KittenML/kitten-tts-nano-0.1"))
        assertFalse(line.contains("(guessed)"))
    }

    @Test
    fun `a resolved model estimated rather than measured rtf is labeled estimated`() {
        val usage = ModelUsage(testDescriptor("m1", "eng1"), sizeBytes = 100L)
        val facts =
            ManageModelFacts(
                hfRepoId = null,
                paramCount = null,
                peakRamBytes = null,
                ramIsMeasured = false,
                realtimeMultiple = 1.5,
                realtimeIsMeasured = false,
            )

        val text = ModelListExport.build(listOf(ExportableModel.from(usage, facts)), emptyList())

        assertTrue(text.lines()[1].contains("~1.5x real-time (estimated)"))
    }

    @Test
    fun `unknown fields are omitted from the line rather than shown as a fake placeholder`() {
        val usage = ModelUsage(testDescriptor("m1", "eng1"), sizeBytes = 100L)
        val facts =
            ManageModelFacts(
                hfRepoId = null,
                paramCount = null,
                peakRamBytes = null,
                ramIsMeasured = false,
                realtimeMultiple = null,
                realtimeIsMeasured = false,
            )

        val line = ModelListExport.build(listOf(ExportableModel.from(usage, facts)), emptyList()).lines()[1]

        assertFalse(line.contains("RAM"))
        assertFalse(line.contains("params"))
        assertFalse(line.contains("real-time"))
        assertFalse(line.contains("huggingface.co"))
    }

    @Test
    fun `an unresolved bundle whose id splits cleanly gets a best-effort guessed hf link`() {
        val unresolved = UnresolvedModelUsage("KittenML_kitten-tts-nano-0.1", sizeBytes = 100L, reason = "no engine")

        val line = ModelListExport.build(emptyList(), listOf(unresolved)).lines()[1]

        assertTrue(line.startsWith("• KittenML_kitten-tts-nano-0.1 — 100 B · no engine yet"))
        assertTrue(line.contains("https://huggingface.co/KittenML/kitten-tts-nano-0.1"))
        assertTrue(line.contains("(guessed)"))
    }

    @Test
    fun `an unresolved bundle whose id has no underscore to split on gets no link at all`() {
        val unresolved = UnresolvedModelUsage("somebundle", sizeBytes = 100L, reason = "no engine")

        val line = ModelListExport.build(emptyList(), listOf(unresolved)).lines()[1]

        assertEquals("• somebundle — 100 B · no engine yet", line)
    }

    @Test
    fun `a resolved model without a curated hf repo id falls back to a guessed link from its model id`() {
        val usage = ModelUsage(testDescriptor("owner_repo-name", "eng1"), sizeBytes = 100L)
        val facts =
            ManageModelFacts(
                hfRepoId = null,
                paramCount = null,
                peakRamBytes = null,
                ramIsMeasured = false,
                realtimeMultiple = null,
                realtimeIsMeasured = false,
            )

        val line = ModelListExport.build(listOf(ExportableModel.from(usage, facts)), emptyList()).lines()[1]

        assertTrue(line.contains("https://huggingface.co/owner/repo-name (guessed)"))
    }

    @Test
    fun `guessHfRepoId splits at the first underscore only and rejects empty segments`() {
        assertEquals("owner/name_with_underscores", ModelListExport.guessHfRepoId("owner_name_with_underscores"))
        assertNull(ModelListExport.guessHfRepoId("nounderscorehere"))
        assertNull(ModelListExport.guessHfRepoId("_leadingunderscore"))
        assertNull(ModelListExport.guessHfRepoId("trailingunderscore_"))
    }
}
