package com.phonetts.app.playback

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.phonetts.core.metrics.GenerationStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The device-confirmed bug ("two notifications, only generation ever visible") traced back to
 * [GenerationNotifier] always posting its OWN row for every generation - which today is EVERY
 * Play tap, since `TtsViewModel.startPlaybackFrom` sets `playing = true` and only then launches
 * generation; export never calls this class. These tests pin the fix: [GenerationNotifier] steps
 * aside (posts nothing, just records progress into [GenerationProgressSignal]) whenever
 * [PlaybackService] already has the ONE media notification in the foreground, and falls back to
 * its own standalone row only when there is genuinely no playback session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var serviceController: ServiceController<PlaybackService>

    @Before
    fun setUp() {
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
    }

    @After
    fun tearDown() {
        // A couple of tests deliberately destroy the service themselves mid-test to simulate the
        // playback session ending; guard against a harmless double-destroy here.
        runCatching { serviceController.destroy() }
        GenerationProgressSignal.current = null
    }

    @Test
    fun `posts its own standalone row when no playback session is active`() {
        val notifier = GenerationNotifier(context)

        notifier.start(totalChunks = 3)

        val active = shadowOf(manager).allNotifications
        assertEquals(1, active.size)
        assertNotEquals(PLAYBACK_NOTIFICATION_ID, active.single().id)
    }

    @Test
    fun `steps aside once the playback notification is in the foreground`() {
        serviceController.startCommand(0, 0) // PlaybackService.ensureForeground() posts id=1
        val notifier = GenerationNotifier(context)

        notifier.start(totalChunks = 4)

        val active = shadowOf(manager).allNotifications
        assertEquals("exactly one row - the media notification, no second one", 1, active.size)
        assertEquals(PLAYBACK_NOTIFICATION_ID, active.single().id)
    }

    @Test
    fun `still records live progress for the media notification to fold in while suppressed`() {
        serviceController.startCommand(0, 0)
        val notifier = GenerationNotifier(context)

        notifier.update(
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 1.0, chunksDone = 2, totalChunks = 9),
        )

        assertEquals(GenerationProgressSignal.Progress(chunksDone = 2, totalChunks = 9), GenerationProgressSignal.current)
    }

    @Test
    fun `a previously posted standalone row is withdrawn once playback catches up`() {
        val notifier = GenerationNotifier(context)
        notifier.start(totalChunks = 4) // no playback session yet - posts its own row
        assertEquals(1, shadowOf(manager).allNotifications.size)

        serviceController.startCommand(0, 0) // playback notification now up
        notifier.update(
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 1.0, chunksDone = 1, totalChunks = 4),
        )

        val active = shadowOf(manager).allNotifications
        assertEquals(1, active.size)
        assertEquals(PLAYBACK_NOTIFICATION_ID, active.single().id)
    }

    @Test
    fun `clear removes any row and resets the shared signal`() {
        val notifier = GenerationNotifier(context)
        notifier.start(totalChunks = 3)

        notifier.clear()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNull(GenerationProgressSignal.current)
    }

    @Test
    fun `resumes its own standalone row once the playback session ends`() {
        serviceController.startCommand(0, 0)
        val notifier = GenerationNotifier(context)
        notifier.start(totalChunks = 2) // suppressed while playback is active

        serviceController.destroy() // PlaybackService.onDestroy() clears isForegroundActive
        notifier.update(
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 1.0, chunksDone = 1, totalChunks = 2),
        )

        val active = shadowOf(manager).allNotifications
        assertEquals(1, active.size)
        assertNotEquals(PLAYBACK_NOTIFICATION_ID, active.single().id)
    }
}
