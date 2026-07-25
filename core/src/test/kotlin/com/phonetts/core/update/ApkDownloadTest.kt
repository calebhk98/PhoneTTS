package com.phonetts.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The in-app APK download's deterministic decisions: progress never fabricates a percentage it
 * can't know, and integrity fails CLOSED (a mismatch is a rejection; a missing hash is "unverified",
 * not "fine").
 */
class ApkDownloadTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun percentIsComputedFromAnAdvertisedTotal() {
        assertEquals(50, ApkDownloadState.Downloading(bytesDone = 50, bytesTotal = 100).percentOrNull())
        assertEquals(0, ApkDownloadState.Downloading(bytesDone = 0, bytesTotal = 100).percentOrNull())
        assertEquals(100, ApkDownloadState.Downloading(bytesDone = 100, bytesTotal = 100).percentOrNull())
    }

    @Test
    fun percentIsNullWhenTheTotalIsUnknownOrZero() {
        assertNull(ApkDownloadState.Downloading(bytesDone = 50, bytesTotal = null).percentOrNull())
        assertNull(ApkDownloadState.Downloading(bytesDone = 50, bytesTotal = 0).percentOrNull())
    }

    @Test
    fun percentIsClampedWhenMoreBytesArriveThanAdvertised() {
        assertEquals(100, ApkDownloadState.Downloading(bytesDone = 250, bytesTotal = 100).percentOrNull())
    }

    @Test
    fun matchingHashVerifies() {
        assertEquals(ApkIntegrity.Verdict.VERIFIED, ApkIntegrity.check(hashA, hashA.uppercase()))
    }

    @Test
    fun differingHashIsAMismatchNotAPass() {
        assertEquals(ApkIntegrity.Verdict.MISMATCH, ApkIntegrity.check(hashA, hashB))
    }

    @Test
    fun anAbsentHashIsUnverifiedNotVerified() {
        assertEquals(ApkIntegrity.Verdict.UNVERIFIED, ApkIntegrity.check(null, hashA))
        assertEquals(ApkIntegrity.Verdict.UNVERIFIED, ApkIntegrity.check("", hashA))
    }
}
