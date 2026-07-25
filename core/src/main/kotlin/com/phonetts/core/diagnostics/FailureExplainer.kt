package com.phonetts.core.diagnostics

import com.phonetts.core.download.hf.HfRateLimitedException
import com.phonetts.core.download.hf.OfflineErrorHint
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a raw [Throwable] into a short, human, ACTIONABLE status line (issue #136): six call sites in
 * `TtsViewModel` alone used to show `"<Action> failed: ${e.message}"` verbatim - literally `"Load
 * failed: null"` for a bare `NullPointerException`, or a native ONNX/JNI crash string, as the ONLY
 * feedback a failure ever gave a non-technical user. A reviewer called this out by name as a top
 * blocker to the app being worth paying for.
 *
 * [explain] always leads with what happened and what the user can DO about it (never a bare exception
 * dump), for the failure classes this app actually produces (verified against the real runtime/engine
 * code, not guessed - see the call sites this replaces). It never fully hides the raw detail either
 * (rule: fail-closed and honest) - an unrecognised exception still gets a useful, actionable message,
 * with the exception's class name (and message, if it has one) appended in a trailing, SECONDARY
 * position so a copy/paste bug report still carries it; it just never LEADS with it.
 *
 * Deliberately pure and dependency-light: reuses [OfflineErrorHint] (the existing offline-detection
 * seam) rather than duplicating its marker list, and matches [HfRateLimitedException] by type since
 * both live in `:core`. It CANNOT reference `com.phonetts.app.UserPickRequiredException` the same way -
 * `:core` must never depend on `:app` - so that one is matched by simple class name, a deliberate,
 * documented cross-module string contract (the same pattern `RuntimeOptions.extras["method"]` already
 * uses between `:app` and an engine module - see `ExecuTorchRuntime`'s kdoc). Likewise ONNX Runtime's
 * `ai.onnxruntime.OrtException` is an Android-only library type `:core` cannot import, so it too is
 * matched by simple name.
 */
object FailureExplainer {
    /** A friendly, actionable line for [throwable] that happened while [context] was under way. */
    fun explain(
        throwable: Throwable,
        context: FailureContext,
    ): String = "${context.headline} - ${causeSentence(throwable)}${technicalDetail(throwable)}"

    // Tried in order of specificity; [unknown] is the only branch that can't return null, so this
    // always resolves to something useful even for a class none of the others recognize. Split into
    // two groups (by exception TYPE, then by exception MESSAGE) purely to stay under detekt's
    // cyclomatic-complexity ceiling on one method - the two groups are still tried in one order.
    private fun causeSentence(throwable: Throwable): String =
        byExceptionType(throwable) ?: byMessagePattern(throwable) ?: unknown()

    private fun byExceptionType(throwable: Throwable): String? =
        rateLimited(throwable)
            ?: userPickRequired(throwable)
            ?: outOfMemory(throwable)
            ?: nativeRuntimeMissing(throwable)
            ?: fileNotFound(throwable)
            ?: offlineHost(throwable)
            ?: timedOut(throwable)

    private fun byMessagePattern(throwable: Throwable): String? =
        diskFull(throwable)
            ?: onnxRuntimeFailure(throwable)
            ?: liteRtRuntimeFailure(throwable)
            ?: genericIoFailure(throwable)

    // A Hugging Face `/api/models` 429 (core/download/hf/HfRateLimit.kt) - expected and self-resolving,
    // never a genuine user-actionable error (see HfBrowseViewModel's countdown-retry UX).
    private fun rateLimited(throwable: Throwable): String? {
        if (throwable !is HfRateLimitedException) return null
        return "the server asked us to slow down (rate limited) - this is temporary and will retry automatically."
    }

    // com.phonetts.app.UserPickRequiredException (AppGraph.kt) - no engine could auto-identify a
    // sideloaded/downloaded bundle. Its own `.explanation` (DetectionFailureExplainer's narration) is
    // the detail surface here - TtsViewModel.sideloadFolder already extracts and shows it in a
    // dedicated panel, so this headline only needs to point at the manual picker, not repeat it.
    private fun userPickRequired(throwable: Throwable): String? {
        if (throwable.javaClass.simpleName != "UserPickRequiredException") return null
        return "we couldn't automatically identify this model - pick its engine manually from the list below."
    }

    // The whole reason this app targets budget hardware explicitly (spec: developed against a 4 GB
    // Galaxy A16, no NPU) - this is the single most likely crash class on the target device.
    private fun outOfMemory(throwable: Throwable): String? {
        if (throwable !is OutOfMemoryError) return null
        return "this device ran out of memory. Close other apps, try a smaller/lighter model, or turn off " +
            "Long-document mode, then try again."
    }

    // Covers UnsatisfiedLinkError (its superclass) too: ExecuTorchRuntime only guards native-lib
    // presence in isAvailable(), not createSession(), so a missing/incompatible native runtime can
    // still surface a raw LinkageError from Module.load() on some devices.
    private fun nativeRuntimeMissing(throwable: Throwable): String? {
        if (throwable !is LinkageError) return null
        return "this device's chip or Android build can't run this model's engine - try a different model, " +
            "this one may not be supported on your hardware."
    }

    private fun fileNotFound(throwable: Throwable): String? {
        if (throwable !is FileNotFoundException) return null
        return "that file couldn't be found - it may have been moved or deleted, or the app lost permission " +
            "to access it. Try picking it again."
    }

    private fun offlineHost(throwable: Throwable): String? {
        if (throwable !is UnknownHostException) return null
        return "no internet connection - check your network and try again."
    }

    private fun timedOut(throwable: Throwable): String? {
        if (throwable !is SocketTimeoutException) return null
        return "the connection timed out - check your network and try again."
    }

    // The standard JVM/Linux wording for ENOSPC on a write (FileOutputStream et al.) - there is no
    // dedicated exception type for it, only this message text.
    private fun diskFull(throwable: Throwable): String? {
        if (throwable !is IOException) return null
        val message = throwable.message?.lowercase() ?: return null
        if ("no space left" !in message && "enospc" !in message) return null
        return "your device is out of storage space - free up space and try again."
    }

    // ai.onnxruntime.OrtException (OnnxRuntime.kt) propagates unwrapped from session create/run - an
    // Android-only library type :core cannot import, hence the name match.
    private fun onnxRuntimeFailure(throwable: Throwable): String? {
        if (throwable.javaClass.simpleName != "OrtException") return null
        return "the on-device ONNX runtime hit an internal error with this model. Try again, or pick a " +
            "different model if this keeps happening."
    }

    // LiteRtRuntime.kt deliberately wraps every load/run failure in a RuntimeException with this exact
    // message prefix, AFTER already writing the full exception + cause chain to the durable error log
    // itself (issue #109's "rich, copyable failure logging" scaffold) - so the detail is already
    // diagnosable without this class repeating it.
    private fun liteRtRuntimeFailure(throwable: Throwable): String? {
        val message = throwable.message ?: return null
        if (!message.startsWith("LiteRT failed to load") && !message.startsWith("LiteRT run failed")) return null
        return "this model's on-device runtime crashed - full technical details were saved to the error log " +
            "on the Help screen. Try again, or pick a different model."
    }

    // Any other IOException: try the existing offline-detection seam first (network drop mid-download,
    // browse, etc.) before falling back to a generic, still-actionable line.
    private fun genericIoFailure(throwable: Throwable): String? {
        if (throwable !is IOException) return null
        OfflineErrorHint.messageFor(throwable.message)?.let { return it }
        return "a network or file error interrupted this. Try again."
    }

    // The unrecognised-exception fallback (rule: fail-closed and honest) - still actionable, and the
    // caller always appends [technicalDetail] afterward so the real cause is never fully hidden.
    private fun unknown(): String =
        "something unexpected went wrong. Try again, and if it keeps happening check the error log on the " +
            "Help screen."

    // Never fully swallows the raw detail (issue #136: "just stop leading with it") - a short,
    // SECONDARY tag naming the exception's class and (if present) its own message, appended after the
    // friendly explanation rather than in place of it.
    private fun technicalDetail(throwable: Throwable): String {
        val className = throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name }
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return if (message != null) " ($className: $message)" else " ($className)"
    }
}
