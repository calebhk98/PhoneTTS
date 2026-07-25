package com.phonetts.core.diagnostics

import com.phonetts.core.download.hf.HfRateLimitedException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam tests for [FailureExplainer] (issue #136) - one per mapped failure class, plus the
 * unknown-exception fallback and the null-message case. Every assertion checks BOTH halves of the
 * "fail-closed and honest" rule: the friendly headline/action leads, and the raw exception detail is
 * still present afterward (never fully swallowed), just not first.
 */
class FailureExplainerTest {
    @Test
    fun outOfMemoryErrorExplainsTheDeviceTargetAndWhatToDo() {
        val message = FailureExplainer.explain(OutOfMemoryError(), FailureContext.LOADING_MODEL)

        assertTrue(message.startsWith("Couldn't load this model"), message)
        assertTrue(message.contains("ran out of memory"), message)
        assertTrue(message.contains("smaller"), message)
        assertTrue(message.contains("OutOfMemoryError"), message)
    }

    @Test
    fun unknownHostExceptionReadsAsNoInternetNotAHostLookupString() {
        val raw = UnknownHostException("huggingface.co")
        val message = FailureExplainer.explain(raw, FailureContext.DOWNLOADING)

        assertTrue(message.startsWith("Couldn't download that"), message)
        assertTrue(message.contains("no internet connection", ignoreCase = true), message)
        assertTrue(message.contains("UnknownHostException"), message)
    }

    @Test
    fun socketTimeoutExceptionReadsAsATimeoutHint() {
        val message = FailureExplainer.explain(SocketTimeoutException("timeout"), FailureContext.DOWNLOADING)

        assertTrue(message.contains("timed out"), message)
        assertTrue(message.contains("SocketTimeoutException"), message)
    }

    @Test
    fun genericIoExceptionReusesOfflineErrorHintWhenTheMessageLooksLikeAConnectivityDrop() {
        val raw = IOException("Failed to connect to huggingface.co")
        val message = FailureExplainer.explain(raw, FailureContext.DOWNLOADING)

        assertTrue(message.contains("No internet connection", ignoreCase = false), message)
    }

    @Test
    fun genericIoExceptionWithoutAConnectivityMarkerStillGetsAnActionableFallback() {
        val raw = IOException("stream ended unexpectedly")
        val message = FailureExplainer.explain(raw, FailureContext.DOWNLOADING)

        assertTrue(message.startsWith("Couldn't download that"), message)
        assertTrue(message.contains("Try again"), message)
        assertTrue(message.contains("stream ended unexpectedly"), message)
    }

    @Test
    fun hfRateLimitedExceptionReadsAsATemporaryAutoRetryNotAnError() {
        val raw = HfRateLimitedException(retryAfterMs = 5_000L, url = "https://huggingface.co/api/models")
        val message = FailureExplainer.explain(raw, FailureContext.DOWNLOADING)

        assertTrue(message.contains("rate limited"), message)
        assertTrue(message.contains("retry automatically"), message)
    }

    @Test
    fun userPickRequiredIsMatchedByNameSinceCoreCannotImportAppsExceptionType() {
        // :core cannot depend on :app's com.phonetts.app.UserPickRequiredException - a same-simple-name
        // stand-in proves the matching is name-based, exactly as documented.
        class UserPickRequiredException : Exception("could not identify model 'x' - a manual engine pick is required")

        val message = FailureExplainer.explain(UserPickRequiredException(), FailureContext.IMPORTING)

        assertTrue(message.contains("couldn't automatically identify"), message)
        assertTrue(message.contains("pick its engine manually"), message)
    }

    @Test
    fun onnxOrtExceptionIsMatchedByNameSinceCoreCannotImportTheAndroidOnlyLibraryType() {
        class OrtException : Exception("Non-zero status code returned while running Add node")

        val message = FailureExplainer.explain(OrtException(), FailureContext.LOADING_MODEL)

        assertTrue(message.contains("ONNX runtime"), message)
        assertTrue(message.contains("OrtException"), message)
    }

    @Test
    fun liteRtLoadFailureIsRecognizedByItsOwnWrappedMessageAndPointsAtTheAlreadyLoggedDetail() {
        val cause = RuntimeException("native crash")
        val wrapped = RuntimeException("LiteRT failed to load '/data/model.tflite' - see the copyable error log", cause)

        val message = FailureExplainer.explain(wrapped, FailureContext.LOADING_MODEL)

        assertTrue(message.contains("error log"), message)
        assertTrue(message.contains("Help screen"), message)
    }

    @Test
    fun liteRtRunFailureIsRecognizedTooNotJustLoad() {
        val wrapped = RuntimeException("LiteRT run failed for '/data/model.tflite' - see the copyable error log")

        val message = FailureExplainer.explain(wrapped, FailureContext.GENERATING)

        assertTrue(message.contains("crashed"), message)
    }

    @Test
    fun nativeLibraryMissingIsExplainedAsAHardwareCompatibilityIssue() {
        val message = FailureExplainer.explain(UnsatisfiedLinkError("libexecutorch.so"), FailureContext.LOADING_MODEL)

        assertTrue(message.contains("chip or Android build"), message)
        assertTrue(message.contains("UnsatisfiedLinkError"), message)
    }

    @Test
    fun fileNotFoundExplainsAMovedOrDeletedFileNotARawPath() {
        val message = FailureExplainer.explain(FileNotFoundException("/tree/document/x"), FailureContext.IMPORTING)

        assertTrue(message.contains("couldn't be found"), message)
        assertTrue(message.contains("Try picking it again"), message)
    }

    @Test
    fun fullDiskIsRecognizedFromTheStandardJvmIoMessage() {
        val message = FailureExplainer.explain(IOException("No space left on device"), FailureContext.EXPORTING)

        assertTrue(message.contains("out of storage space"), message)
    }

    @Test
    fun fullDiskDetectionIsCaseInsensitive() {
        val raw = IOException("Write failed: ENOSPC (No Space Left On Device)")
        val message = FailureExplainer.explain(raw, FailureContext.EXPORTING)

        assertTrue(message.contains("out of storage space"), message)
    }

    @Test
    fun nullMessageNeverLeaksTheLiteralWordNull() {
        val message = FailureExplainer.explain(NullPointerException(), FailureContext.PLAYING)

        assertFalse(message.contains("null"), message)
        assertTrue(message.startsWith("Couldn't play that"), message)
        assertTrue(message.contains("NullPointerException"), message)
    }

    @Test
    fun blankMessageIsTreatedTheSameAsNoMessage() {
        val message = FailureExplainer.explain(RuntimeException(""), FailureContext.EXPORTING)

        assertFalse(message.contains("null"), message)
        val expected =
            "Couldn't export audio - something unexpected went wrong. Try again, and if it keeps " +
                "happening check the error log on the Help screen. (RuntimeException)"
        assertEquals(expected, message)
    }

    @Test
    fun unknownExceptionStillProducesAnActionableFallbackAndKeepsTheDetailSecondary() {
        val raw = IllegalStateException("engine failed to load")
        val message = FailureExplainer.explain(raw, FailureContext.GENERATING)

        assertTrue(message.startsWith("Couldn't generate audio - something unexpected went wrong"), message)
        // The technical detail is present but AFTER the friendly explanation, never leading it.
        val friendlyIndex = message.indexOf("something unexpected went wrong")
        val detailIndex = message.indexOf("IllegalStateException")
        assertTrue(friendlyIndex in 0..<detailIndex, message)
        assertTrue(message.contains("engine failed to load"), message)
    }

    @Test
    fun everyContextGetsItsOwnHeadline() {
        val e = IllegalStateException("boom")
        assertTrue(FailureExplainer.explain(e, FailureContext.LOADING_MODEL).startsWith("Couldn't load this model"))
        assertTrue(FailureExplainer.explain(e, FailureContext.GENERATING).startsWith("Couldn't generate audio"))
        assertTrue(FailureExplainer.explain(e, FailureContext.PLAYING).startsWith("Couldn't play that"))
        assertTrue(FailureExplainer.explain(e, FailureContext.EXPORTING).startsWith("Couldn't export audio"))
        assertTrue(FailureExplainer.explain(e, FailureContext.DOWNLOADING).startsWith("Couldn't download that"))
        assertTrue(FailureExplainer.explain(e, FailureContext.IMPORTING).startsWith("Couldn't import that"))
        assertTrue(FailureExplainer.explain(e, FailureContext.MANAGING_MODEL).startsWith("Couldn't complete that"))
    }
}
