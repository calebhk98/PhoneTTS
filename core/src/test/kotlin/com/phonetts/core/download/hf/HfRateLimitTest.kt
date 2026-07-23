package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HfRateLimitTest {
    @Test
    fun parsesResetSecondsFromRateLimitHeader() {
        assertEquals(274L, HfRateLimitHeader.parseResetSeconds("\"api\";r=499;t=274"))
        assertEquals(0L, HfRateLimitHeader.parseResetSeconds("\"api\";r=0;t=0"))
    }

    @Test
    fun returnsNullForMissingOrMalformedRateLimitHeader() {
        assertNull(HfRateLimitHeader.parseResetSeconds(null))
        assertNull(HfRateLimitHeader.parseResetSeconds(""))
        assertNull(HfRateLimitHeader.parseResetSeconds("\"api\";r=499"), "no t= token")
        assertNull(HfRateLimitHeader.parseResetSeconds("\"api\";r=1;t=abc"), "non-numeric t=")
    }

    @Test
    fun parsesPlainRetryAfterDefensively() {
        assertEquals(30L, HfRateLimitHeader.parseRetryAfterSeconds("30"))
        assertNull(HfRateLimitHeader.parseRetryAfterSeconds(null))
        assertNull(
            HfRateLimitHeader.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"),
            "HTTP-date form unsupported",
        )
    }

    @Test
    fun honorsServerHintAndNeverRetriesBeforeItsReset() {
        // With a 40s server hint (under the cap), downward jitter must not pull the wait below 40s.
        val minJitter = HfRateLimitBackoff.nextDelayMs(1, serverHintMs = 40_000L, random = { 0.0 })
        assertTrue(minJitter >= 40_000L, "must not retry before the server's stated reset, got $minJitter")
    }

    @Test
    fun capsAServerHintLongerThanTheMaxBackoff() {
        // HF windows can be ~300s; we never sleep that long silently — the cap bounds it.
        val delay = HfRateLimitBackoff.nextDelayMs(1, serverHintMs = 274_000L, random = { 0.5 })
        assertEquals(HfRateLimitBackoff.MAX_BACKOFF_MS, delay)
    }

    @Test
    fun fallsBackToExponentialBackoffWhenNoHint() {
        // No jitter (0.5 -> factor 1.0): 2s, 4s, 8s for the first three attempts.
        assertEquals(2_000L, HfRateLimitBackoff.nextDelayMs(1, serverHintMs = null, random = { 0.5 }))
        assertEquals(4_000L, HfRateLimitBackoff.nextDelayMs(2, serverHintMs = null, random = { 0.5 }))
        assertEquals(8_000L, HfRateLimitBackoff.nextDelayMs(3, serverHintMs = null, random = { 0.5 }))
    }

    @Test
    fun exponentialBackoffStaysWithinTheCap() {
        val delay = HfRateLimitBackoff.nextDelayMs(20, serverHintMs = null, random = { 1.0 })
        assertTrue(delay <= HfRateLimitBackoff.MAX_BACKOFF_MS, "cap must bound a large attempt count, got $delay")
    }
}
