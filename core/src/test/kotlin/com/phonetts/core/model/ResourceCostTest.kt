package com.phonetts.core.model

import com.phonetts.core.engine.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResourceCostTest {
    @Test
    fun `unknown carries no peak ram so the ui shows no fabricated number`() {
        assertNull(ResourceCost.UNKNOWN.approxPeakRamBytes)
    }

    @Test
    fun `peakRamMebibytes converts whole mebibytes to bytes`() {
        assertEquals(200L * 1024 * 1024, ResourceCost.peakRamMebibytes(200).approxPeakRamBytes)
    }

    @Test
    fun `a known peak ram must be positive`() {
        assertFailsWith<IllegalArgumentException> { ResourceCost(approxPeakRamBytes = 0L) }
        assertFailsWith<IllegalArgumentException> { ResourceCost(approxPeakRamBytes = -1L) }
    }

    @Test
    fun `descriptor defaults to unknown cost and carries the fact when supplied`() {
        val voices = listOf(Voice("v0", "Voice 0", "en"))
        val bare =
            ModelDescriptor(
                modelId = "m1",
                engineId = "eng",
                displayName = "M1",
                origin = Origin.BUILT_IN,
                sampleRate = 22_050,
                voices = voices,
                defaultVoiceId = "v0",
            )
        assertEquals(ResourceCost.UNKNOWN, bare.resourceCost)

        val withCost = bare.copy(resourceCost = ResourceCost.peakRamMebibytes(512))
        assertEquals(512L * 1024 * 1024, withCost.resourceCost.approxPeakRamBytes)
    }
}
