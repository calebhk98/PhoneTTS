package com.phonetts.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceRamFitTest {
    private val fourGb = 4L * 1024 * 1024 * 1024
    private val threePointFiveGb = 3_500L * 1024 * 1024
    private val fiveGb = 5L * 1024 * 1024 * 1024

    @Test
    fun `a merely tight fit never warns - 3point5GB peak on a 4GB phone fits`() {
        assertFalse(DeviceRamFit.modelExceedsDeviceRam(threePointFiveGb, fourGb))
    }

    @Test
    fun `a peak bigger than total ram does not fit`() {
        assertTrue(DeviceRamFit.modelExceedsDeviceRam(fiveGb, fourGb))
    }

    @Test
    fun `an unknown peak is never treated as a failure to fit`() {
        assertFalse(DeviceRamFit.modelExceedsDeviceRam(null, fourGb))
    }

    @Test
    fun `a peak exactly equal to total ram still fits`() {
        assertFalse(DeviceRamFit.modelExceedsDeviceRam(fourGb, fourGb))
    }

    @Test
    fun `an unreadable total ram (zero) is never treated as a failure to fit`() {
        assertFalse(DeviceRamFit.modelExceedsDeviceRam(fiveGb, 0L))
    }

    @Test
    fun `a nonzero reserve is honored when a caller explicitly supplies one`() {
        val reserve = 512L * 1024 * 1024
        assertTrue(DeviceRamFit.modelExceedsDeviceRam(fourGb, fourGb, reserveBytes = reserve))
    }
}
