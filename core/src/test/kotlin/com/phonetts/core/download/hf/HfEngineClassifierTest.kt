package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals

class HfEngineClassifierTest {
    // Deliberately mirrors real registered engine ids (kokoro, piper, kittentts, ...) to prove the
    // matching works off the registry's ids, never a hardcoded model-name table.
    private val engineIds = listOf("kokoro", "piper", "kittentts", "melotts", "cosyvoice2")

    @Test
    fun matchesAnEngineWhoseIdTokenAppearsInTheRepoId() {
        val model = HfModelSummary(id = "hexgrad/Kokoro-82M")
        assertEquals("kokoro", HfEngineClassifier.engineOf(model, engineIds))
    }

    @Test
    fun matchesAnEngineWhoseIdTokenAppearsInATag() {
        val model = HfModelSummary(id = "someuser/voice-pack", tags = listOf("tts", "piper"))
        assertEquals("piper", HfEngineClassifier.engineOf(model, engineIds))
    }

    @Test
    fun matchesAcrossHyphenatedRepoNames() {
        val model = HfModelSummary(id = "KittenML/kitten-tts-nano-0.1")
        assertEquals("kittentts", HfEngineClassifier.engineOf(model, engineIds))
    }

    @Test
    fun unmatchedRepoFallsBackToUnknown() {
        val model = HfModelSummary(id = "acme/mystery-voice", tags = listOf("tts"))
        assertEquals(HfEngineClassifier.UNKNOWN, HfEngineClassifier.engineOf(model, engineIds))
    }

    @Test
    fun availableEnginesListsPresentEnginesThenUnknownLast() {
        val results =
            listOf(
                HfModelSummary(id = "hexgrad/Kokoro-82M"),
                HfModelSummary(id = "rhasspy/piper-voices"),
                HfModelSummary(id = "acme/mystery"),
            )
        val labels = HfEngineClassifier.engineLabels(results, engineIds)
        assertEquals(listOf("kokoro", "piper", HfEngineClassifier.UNKNOWN), HfEngineClassifier.availableEngines(labels))
    }

    @Test
    fun filterByEngineKeepsOnlyThatEngineAndUnknownIsSelectable() {
        val results =
            listOf(
                HfModelSummary(id = "hexgrad/Kokoro-82M"),
                HfModelSummary(id = "acme/mystery"),
            )
        val labels = HfEngineClassifier.engineLabels(results, engineIds)
        val kokoro = HfEngineClassifier.filterByEngine(results, labels, "kokoro")
        assertEquals(listOf("hexgrad/Kokoro-82M"), kokoro.map { it.id })
        val other = HfEngineClassifier.filterByEngine(results, labels, HfEngineClassifier.UNKNOWN)
        assertEquals(listOf("acme/mystery"), other.map { it.id })
    }

    @Test
    fun filterByEngineWithNullIsANoOp() {
        val results = listOf(HfModelSummary(id = "hexgrad/Kokoro-82M"))
        assertEquals(results.map { it.id }, HfEngineClassifier.filterByEngine(results, emptyMap(), null).map { it.id })
    }
}
