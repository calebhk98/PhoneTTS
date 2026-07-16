package com.phonetts.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ONE_MINUTE_MILLIS = 60_000L

class SleepTimerTest {
    /** A mutable fake clock so tests can move time forward deterministically. */
    private class FakeClock(startAtMillis: Long = 0L) {
        var millis: Long = startAtMillis
        val now: () -> Long = { millis }
    }

    @Test
    fun reportsFullDurationRemainingImmediatelyAfterStart() {
        val clock = FakeClock()
        val timer = SleepTimer(clock.now)

        timer.start(ONE_MINUTE_MILLIS) {}

        assertEquals(ONE_MINUTE_MILLIS, timer.remainingMillis())
        assertTrue(timer.isRunning)
    }

    @Test
    fun remainingMillisCountsDownAsTheClockAdvances() {
        val clock = FakeClock()
        val timer = SleepTimer(clock.now)
        timer.start(ONE_MINUTE_MILLIS) {}

        clock.millis += 20_000L

        assertEquals(40_000L, timer.remainingMillis())
    }

    @Test
    fun remainingMillisClampsToZeroPastTheDeadline() {
        val clock = FakeClock()
        val timer = SleepTimer(clock.now)
        timer.start(ONE_MINUTE_MILLIS) {}

        clock.millis += ONE_MINUTE_MILLIS * 2

        assertEquals(0L, timer.remainingMillis())
    }

    @Test
    fun tickDoesNotFireBeforeTheDeadline() {
        val clock = FakeClock()
        val timer = SleepTimer(clock.now)
        var expired = false
        timer.start(ONE_MINUTE_MILLIS) { expired = true }

        clock.millis += ONE_MINUTE_MILLIS - 1
        timer.tick()

        assertFalse(expired)
        assertTrue(timer.isRunning)
    }

    @Test
    fun tickFiresOnExpireExactlyOnceOncePastTheDeadline() {
        val clock = FakeClock()
        val timer = SleepTimer(clock.now)
        var expiredCount = 0
        timer.start(ONE_MINUTE_MILLIS) { expiredCount++ }

        clock.millis += ONE_MINUTE_MILLIS
        timer.tick()
        timer.tick() // repeated ticks after expiry must not re-fire
        clock.millis += ONE_MINUTE_MILLIS
        timer.tick()

        assertEquals(1, expiredCount)
        assertFalse(timer.isRunning)
    }

    @Test
    fun cancelDisarmsTheCountdownWithoutFiring() {
        val clock = FakeClock()
        val timer = SleepTimer(clock.now)
        var expired = false
        timer.start(ONE_MINUTE_MILLIS) { expired = true }

        timer.cancel()
        clock.millis += ONE_MINUTE_MILLIS * 2
        timer.tick()

        assertFalse(expired)
        assertFalse(timer.isRunning)
        assertEquals(0L, timer.remainingMillis())
    }

    @Test
    fun startingAgainReplacesAnyCountdownAlreadyRunning() {
        val clock = FakeClock()
        val timer = SleepTimer(clock.now)
        var firstFired = false
        var secondFired = false
        timer.start(ONE_MINUTE_MILLIS) { firstFired = true }

        clock.millis += 30_000L
        timer.start(ONE_MINUTE_MILLIS) { secondFired = true }

        assertEquals(ONE_MINUTE_MILLIS, timer.remainingMillis())
        clock.millis += ONE_MINUTE_MILLIS
        timer.tick()

        assertFalse(firstFired)
        assertTrue(secondFired)
    }

    @Test
    fun rejectsANonPositiveDuration() {
        val timer = SleepTimer { 0L }

        assertFailsWith<IllegalArgumentException> { timer.start(0L) {} }
        assertFailsWith<IllegalArgumentException> { timer.start(-1L) {} }
    }
}
