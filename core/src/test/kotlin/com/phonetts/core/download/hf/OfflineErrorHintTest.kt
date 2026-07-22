package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflineErrorHintTest {
    private val friendly = "No internet connection — check your network and try again."

    @Test
    fun recognizesDnsFailureAsAConnectivityProblem() {
        val raw = "Unable to resolve host \"huggingface.co\": No address associated with hostname"
        assertEquals(friendly, OfflineErrorHint.messageFor(raw))
    }

    @Test
    fun recognizesOtherOfflineMarkersCaseInsensitively() {
        assertEquals(friendly, OfflineErrorHint.messageFor("Failed to connect to huggingface.co"))
        assertEquals(friendly, OfflineErrorHint.messageFor("Network is unreachable"))
    }

    @Test
    fun leavesAGenuineServerErrorAlone() {
        assertNull(OfflineErrorHint.messageFor("HTTP 404 Not Found"))
        assertNull(OfflineErrorHint.messageFor("Couldn't download model.onnx: download exceeds cap"))
    }

    @Test
    fun blankOrNullIsNotAConnectivityProblem() {
        assertNull(OfflineErrorHint.messageFor(null))
        assertNull(OfflineErrorHint.messageFor("   "))
    }

    @Test
    fun humanizeReplacesOfflineMessagesButPassesGenuineErrorsThrough() {
        assertEquals(friendly, OfflineErrorHint.humanize("unable to resolve host"))
        assertEquals("HTTP 403 Forbidden", OfflineErrorHint.humanize("HTTP 403 Forbidden"))
    }
}
