package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals

class HfResumeTest {
    @Test
    fun restartsWhenNothingIsOnDiskYet() {
        val decision = HfResume.decide(existingBytes = 0, expectedBytes = 8_400_000)
        assertEquals(ResumeAction.RESTART, decision.action)
        assertEquals(0L, decision.offsetBytes)
    }

    @Test
    fun skipsAFileThatIsAlreadyComplete() {
        val decision = HfResume.decide(existingBytes = 1200, expectedBytes = 1200)
        assertEquals(ResumeAction.SKIP, decision.action)
    }

    @Test
    fun resumesFromTheOnDiskOffsetForAPartialFile() {
        val decision = HfResume.decide(existingBytes = 2_000_000, expectedBytes = 8_400_000)
        assertEquals(ResumeAction.RESUME, decision.action)
        assertEquals(2_000_000L, decision.offsetBytes)
    }

    @Test
    fun restartsWhenTheRepoDidNotAdvertiseASize() {
        // A partial file we can't trust (no expected size) must not be resumed - refetch instead.
        val decision = HfResume.decide(existingBytes = 500, expectedBytes = null)
        assertEquals(ResumeAction.RESTART, decision.action)
        assertEquals(0L, decision.offsetBytes)
    }

    @Test
    fun restartsWhenTheLocalFileIsLargerThanAdvertised() {
        val decision = HfResume.decide(existingBytes = 9_000_000, expectedBytes = 8_400_000)
        assertEquals(ResumeAction.RESTART, decision.action)
        assertEquals(0L, decision.offsetBytes)
    }
}
