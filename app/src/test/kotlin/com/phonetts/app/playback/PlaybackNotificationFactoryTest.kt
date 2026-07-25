package com.phonetts.app.playback

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam tests for the notification the device-confirmed bug is actually about: the ONE media
 * notification a user should see on the lock screen / in the shade while playing (and, per the
 * fix, while a generation folds its progress into this row instead of posting a second one - see
 * [GenerationNotifier] / [GenerationProgressSignal]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackNotificationFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val factory = PlaybackNotificationFactory(context)

    @After
    fun tearDown() {
        // GenerationProgressSignal is process-global state shared with GenerationNotifier; reset it
        // so one test's write can't leak into another's assertions.
        GenerationProgressSignal.current = null
    }

    @Test
    fun `playing notification has the expected transport actions`() {
        val notification = factory.build(paused = false, title = "My document", sessionToken = null)

        // prev-paragraph, play/pause, next-paragraph, stop (rule 3 in the task: verify the actions).
        assertEquals(4, notification.actions?.size)
        assertEquals("Playing", contentText(notification))
        assertEquals("My document", contentTitle(notification))
        // Ongoing while actively playing, so the OS won't let it be swiped away mid-playback.
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0 || isOngoing(notification))
    }

    @Test
    fun `paused notification is not ongoing and shows Play`() {
        val notification = factory.build(paused = true, title = null, sessionToken = null)

        assertEquals("Paused", contentText(notification))
        assertEquals("PhoneTTS", contentTitle(notification)) // DEFAULT_NOTIFICATION_TITLE fallback
    }

    @Test
    fun `folds live generation progress into the subtitle instead of a second notification`() {
        GenerationProgressSignal.current = GenerationProgressSignal.Progress(chunksDone = 2, totalChunks = 10)

        val notification = factory.build(paused = false, title = "Doc", sessionToken = null)

        assertEquals("Playing · Generating sentence 3 of 10", contentText(notification))
    }

    @Test
    fun `paused notification also folds in generation progress`() {
        GenerationProgressSignal.current = GenerationProgressSignal.Progress(chunksDone = 0, totalChunks = 0)

        val notification = factory.build(paused = true, title = "Doc", sessionToken = null)

        assertEquals("Paused · Generating sentence 1…", contentText(notification))
    }

    @Test
    fun `subtitle reverts to plain state once generation clears the signal`() {
        GenerationProgressSignal.current = GenerationProgressSignal.Progress(chunksDone = 1, totalChunks = 5)
        GenerationProgressSignal.current = null

        val notification = factory.build(paused = false, title = null, sessionToken = null)

        assertEquals("Playing", contentText(notification))
    }

    @Test
    fun `content intent opens MainActivity`() {
        val notification = factory.build(paused = false, title = null, sessionToken = null)
        assertNotNull(notification.contentIntent)
    }

    private fun contentText(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun contentTitle(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun isOngoing(notification: Notification): Boolean =
        notification.flags and Notification.FLAG_NO_CLEAR != 0
}
