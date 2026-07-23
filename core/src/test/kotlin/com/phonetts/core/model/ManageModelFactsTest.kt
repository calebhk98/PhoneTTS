package com.phonetts.core.model

import com.phonetts.core.download.builtin.BuiltInCatalog
import com.phonetts.core.download.builtin.PiperVoicesIndex
import com.phonetts.core.testing.testDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManageModelFactsTest {
    @Test
    fun `recovers the hf repo id for a curated built-in model by its catalog id`() {
        val kitten = BuiltInCatalog.KITTEN_NANO
        assertEquals(kitten.repoId, InstalledModelFacts.hfRepoId(kitten.id))
    }

    @Test
    fun `a model id absent from every curated catalog yields no link rather than a guess`() {
        assertNull(InstalledModelFacts.hfRepoId("some-browsed-or-sideloaded-bundle"))
    }

    @Test
    fun `a piper voice beyond the curated catalog entry still resolves by its id prefix`() {
        // Not BuiltInCatalog.PIPER_LESSAC itself (that hits the catalog lookup above) - any other
        // voice from the dynamically-fetched PiperVoicesIndex list, recognized purely by its
        // `piper-` id prefix since the full list isn't retained as static data to search.
        assertEquals(PiperVoicesIndex.REPO_ID, InstalledModelFacts.hfRepoId("piper-de_DE-thorsten-medium"))
    }

    @Test
    fun `zero or negative on-disk size yields unknown param count and unknown predicted speed`() {
        val descriptor = testDescriptor("m1", "eng1")

        val facts = InstalledModelFacts.of(descriptor, sizeBytes = 0L, observedPeakRamBytes = null)

        assertNull(facts.paramCount)
        assertNull(facts.realtimeMultiple)
        assertFalse(facts.realtimeIsMeasured)
    }

    @Test
    fun `a known on-disk size yields an estimated param count and a predicted realtime multiple`() {
        val descriptor = testDescriptor("m1", "eng1")

        val facts = InstalledModelFacts.of(descriptor, sizeBytes = 200_000_000L, observedPeakRamBytes = null)

        assertEquals(100_000_000L, facts.paramCount) // fp16 default: 200MB / 2 bytes-per-param
        assertTrue(facts.realtimeMultiple != null && facts.realtimeMultiple!! > 0.0)
        assertFalse(facts.realtimeIsMeasured)
    }

    @Test
    fun `measured benchmark history beats the predicted speed and is labeled measured`() {
        val descriptor = testDescriptor("m1", "eng1")

        // wall/audio RTF of 0.25 -> 1 / 0.25 = 4x real-time.
        val facts =
            InstalledModelFacts.of(
                descriptor,
                sizeBytes = 200_000_000L,
                observedPeakRamBytes = null,
                measuredRealTimeFactors = listOf(0.25, 0.25),
            )

        assertEquals(4.0, facts.realtimeMultiple)
        assertTrue(facts.realtimeIsMeasured)
    }

    @Test
    fun `non-positive measured rtf samples are ignored rather than dividing by zero`() {
        val descriptor = testDescriptor("m1", "eng1")

        val facts =
            InstalledModelFacts.of(
                descriptor,
                sizeBytes = 200_000_000L,
                observedPeakRamBytes = null,
                measuredRealTimeFactors = listOf(0.0, -1.0),
            )

        assertFalse(facts.realtimeIsMeasured)
    }

    @Test
    fun `an observed peak ram is preferred over the descriptor a-priori estimate and marked measured`() {
        val descriptor = testDescriptor("m1", "eng1").copy(resourceCost = ResourceCost.peakRamMebibytes(512))

        val measured = InstalledModelFacts.of(descriptor, sizeBytes = 1L, observedPeakRamBytes = 900L)
        assertEquals(900L, measured.peakRamBytes)
        assertTrue(measured.ramIsMeasured)

        val estimatedOnly = InstalledModelFacts.of(descriptor, sizeBytes = 1L, observedPeakRamBytes = null)
        assertEquals(512L * 1024 * 1024, estimatedOnly.peakRamBytes)
        assertFalse(estimatedOnly.ramIsMeasured)
    }

    @Test
    fun `no observed peak and no descriptor estimate leaves ram unknown`() {
        val descriptor = testDescriptor("m1", "eng1")

        val facts = InstalledModelFacts.of(descriptor, sizeBytes = 1L, observedPeakRamBytes = null)

        assertNull(facts.peakRamBytes)
        assertFalse(facts.ramIsMeasured)
    }
}
