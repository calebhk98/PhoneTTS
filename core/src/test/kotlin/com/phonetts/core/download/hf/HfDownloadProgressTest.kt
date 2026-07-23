package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HfDownloadProgressTest {
    @Test
    fun tooLittleElapsedTimeYieldsNoThroughputReadingYet() {
        val progress = HfDownloadProgress(bytesDone = 10_000, bytesTotal = 100_000, startedAtMs = 0)

        assertNull(progress.bytesPerSecond(nowMs = 200), "under a second elapsed - no rate yet, not a wild guess")
        assertNull(progress.etaSeconds(nowMs = 200))
    }

    @Test
    fun computesThroughputAndEtaFromMeasuredBytesOverElapsedTime() {
        val progress = HfDownloadProgress(bytesDone = 5_000_000, bytesTotal = 20_000_000, startedAtMs = 0)

        val rate = progress.bytesPerSecond(nowMs = 5_000)
        assertEquals(1_000_000.0, rate)

        val eta = progress.etaSeconds(nowMs = 5_000)
        assertEquals(15.0, eta)
    }

    @Test
    fun unknownTotalSizeYieldsNoEtaEvenWithAGoodThroughputReading() {
        val progress = HfDownloadProgress(bytesDone = 5_000_000, bytesTotal = null, startedAtMs = 0)

        assertNull(progress.etaSeconds(nowMs = 5_000), "never fabricate an ETA without a known total")
    }

    @Test
    fun noBytesYetYieldsNoThroughputOrEta() {
        val progress = HfDownloadProgress(bytesDone = 0, bytesTotal = 1_000, startedAtMs = 0)

        assertNull(progress.bytesPerSecond(nowMs = 5_000))
        assertNull(progress.etaSeconds(nowMs = 5_000))
    }
}
