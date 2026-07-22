package com.phonetts.core.prefs

import com.phonetts.core.model.ResourceCost
import com.phonetts.core.testing.testDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResourceUsageStoreTest {
    @Test
    fun `observedPeakRam is null until something is recorded`() {
        val store = ResourceUsageStore(InMemoryPreferenceStore())
        assertNull(store.observedPeakRam("m1"))
    }

    @Test
    fun `record then read round-trips the observed peak`() {
        val store = ResourceUsageStore(InMemoryPreferenceStore())
        store.recordPeakRam("m1", 300_000_000L)
        assertEquals(300_000_000L, store.observedPeakRam("m1"))
    }

    @Test
    fun `recording keeps the max observed peak as a safe ceiling`() {
        val store = ResourceUsageStore(InMemoryPreferenceStore())
        store.recordPeakRam("m1", 300_000_000L)
        store.recordPeakRam("m1", 250_000_000L)
        store.recordPeakRam("m1", 400_000_000L)
        assertEquals(400_000_000L, store.observedPeakRam("m1"))
    }

    @Test
    fun `a non-positive observed peak is rejected`() {
        val store = ResourceUsageStore(InMemoryPreferenceStore())
        assertFailsWith<IllegalArgumentException> { store.recordPeakRam("m1", 0L) }
    }

    @Test
    fun `reads fail closed on a corrupt value`() {
        val backing = InMemoryPreferenceStore()
        backing.putString("resource_peak_ram.m1", "not-a-number")
        assertNull(ResourceUsageStore(backing).observedPeakRam("m1"))
    }

    @Test
    fun `forget clears an observed peak`() {
        val store = ResourceUsageStore(InMemoryPreferenceStore())
        store.recordPeakRam("m1", 100_000_000L)
        store.forget("m1")
        assertNull(store.observedPeakRam("m1"))
    }

    @Test
    fun `peakRamEstimate prefers a real observed peak over the descriptor estimate`() {
        val store = ResourceUsageStore(InMemoryPreferenceStore())
        val descriptor = testDescriptor("m1", "eng").copy(resourceCost = ResourceCost.peakRamMebibytes(100))
        store.recordPeakRam("m1", 512_000_000L)
        assertEquals(512_000_000L, store.peakRamEstimate(descriptor))
    }

    @Test
    fun `peakRamEstimate falls back to the descriptor estimate, then to null`() {
        val store = ResourceUsageStore(InMemoryPreferenceStore())
        val withEstimate = testDescriptor("m1", "eng").copy(resourceCost = ResourceCost.peakRamMebibytes(100))
        assertEquals(100L * 1024 * 1024, store.peakRamEstimate(withEstimate))

        val unknown = testDescriptor("m2", "eng")
        assertNull(store.peakRamEstimate(unknown))
    }
}
