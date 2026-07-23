package com.phonetts.core.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The download guard is free-storage-based, not a fixed 2 GB ceiling - a large model must download
 * whenever it actually fits (this is the SoulX-Singer `model-svc.pt` > 2 GB regression).
 */
class DownloadStorageCapTest {
    private val gb = 1024L * 1024 * 1024
    private val margin = DownloadStorageCap.DEFAULT_MARGIN_BYTES

    @Test
    fun `a large file well within free storage is allowed`() {
        // 3 GB file, 10 GB free -> fits.
        assertFalse(DownloadStorageCap.exceedsFreeStorage(fileBytes = 3 * gb, freeBytes = 10 * gb))
    }

    @Test
    fun `a file bigger than the old 2 GB cap is allowed when it fits`() {
        // The exact regression: > 2 GB used to be rejected outright; now it downloads if it fits.
        assertFalse(DownloadStorageCap.exceedsFreeStorage(fileBytes = 5 * gb, freeBytes = 20 * gb))
    }

    @Test
    fun `a file larger than free storage minus the margin is rejected`() {
        // 9.9 GB file, 10 GB free, 256 MB margin -> does not fit.
        assertTrue(DownloadStorageCap.exceedsFreeStorage(fileBytes = 10 * gb - margin + 1, freeBytes = 10 * gb))
    }

    @Test
    fun `an unknown size is never pre-rejected`() {
        assertFalse(DownloadStorageCap.exceedsFreeStorage(fileBytes = null, freeBytes = 1 * gb))
    }

    @Test
    fun `the cap is free space minus the margin, never negative`() {
        assertEquals(10 * gb - margin, DownloadStorageCap.capFor(freeBytes = 10 * gb))
        assertEquals(0L, DownloadStorageCap.capFor(freeBytes = margin / 2))
    }
}
