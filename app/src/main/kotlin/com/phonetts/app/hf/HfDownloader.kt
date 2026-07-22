package com.phonetts.app.hf

import android.os.StatFs
import com.phonetts.app.ModelStorage
import com.phonetts.core.download.DownloadStorageCap
import com.phonetts.core.download.SafePath
import com.phonetts.core.download.hf.DownloadDiagnosticsLog
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.download.hf.HfDownloadItem
import com.phonetts.core.download.hf.HfResume
import com.phonetts.core.download.hf.OfflineErrorHint
import com.phonetts.core.download.hf.ResumeAction
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.sideload.ModelImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads a Hugging Face repo's files into app-private storage, then hands the folder to the
 * tested core [ModelImporter] — which reads it, resolves it (auto-detect that fails closed, else
 * the user pick), and catalogs it. So a browsed model becomes usable with NO engine-specific code.
 *
 * Weights are streamed to disk (never held in memory) and downloads are **resumable**: each file
 * is fetched into its final path, so a fetch interrupted by a dropped connection or the app's
 * process being reclaimed mid-download continues from where it left off next time (HTTP `Range`),
 * and an already-complete file is skipped. The resume policy is the tested [HfResume] seam in
 * `:core`; this class only supplies the on-disk size and acts on the verdict. Cancellation is
 * cooperative — the caller cancels the coroutine and the partial file is left on disk so a later
 * retry resumes rather than restarts.
 *
 * HF ETags are not stable content hashes, so there is no manifest hash to check against for an
 * arbitrary repo; integrity for browsed models rests on HTTPS + the resolver refusing anything it
 * can't identify. (Curated/manifest downloads still verify SHA-256 via
 * com.phonetts.core.download.Sha256Verifier.)
 *
 * A dropped connection mid-file ("Software caused connection abort", "unexpected end of stream",
 * a socket reset, a premature EOF) is common on flaky mobile networks and is NOT treated as a hard
 * failure: each file fetch is retried with exponential backoff (the resume logic above means a
 * retry continues from the partial file, not from scratch — see the private `fetch` function).
 * Only after the retries are exhausted, or for an obviously permanent error (HTTP 404/403, the
 * byte-cap guard), does this surface a failure — always naming the file and the reason, never a
 * bare URL (the previous behavior for e.g. sesame/csm-1b, whose surfaced "error" was literally a
 * `resolve/main/...` URL).
 */
class HfDownloader(
    private val filesDir: File,
    private val importer: ModelImporter,
    private val userAgent: Map<String, String> = HfCatalog.USER_AGENT,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    // The per-file guard is free-storage-based, not a fixed ceiling — a large model (a multi-GB
    // weights file) must download whenever it actually fits, only failing when it genuinely won't.
    // Injectable so the guard stays deterministic in tests.
    private val freeBytes: () -> Long = { StatFs(filesDir.path).availableBytes },
    /** Persistent, user-viewable record of download failures and "downloaded, no engine yet"
     * imports (see [DownloadDiagnosticsLog]). Defaults to a small file under [filesDir] so callers
     * that already construct an [HfDownloader] with just a base dir + importer (the common case)
     * get it for free; the Browse screen reads/records through this same instance. */
    val diagnosticsLog: DownloadDiagnosticsLog = DownloadDiagnosticsLog(File(filesDir, DIAGNOSTICS_LOG_FILENAME)),
) {
    /**
     * @param onBytesProgress cumulative bytes written across ALL of [items] so far, plus the
     * plan's total size in bytes — or `null` for the total when any item's size wasn't advertised
     * by the repo tree (spec: never present a fabricated total). Reported at most every
     * [BYTES_PROGRESS_STEP] bytes so a large weight file doesn't flood the caller's state updates.
     */
    suspend fun downloadAndImport(
        modelId: String,
        items: List<HfDownloadItem>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        onBytesProgress: (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): ModelDescriptor =
        withContext(Dispatchers.IO) {
            val destination = ModelStorage.modelDir(filesDir, modelId)
            destination.mkdirs()
            val bytesTotal = totalBytesOrNull(items)
            var priorBytes = 0L
            items.forEachIndexed { index, item ->
                // Defense-in-depth against path traversal (HfDownloadPlan already validates).
                SafePath.require(item.relativePath)
                val target = File(destination, item.relativePath)
                val base = priorBytes
                fetch(item, target) { withinFile -> onBytesProgress(base + withinFile, bytesTotal) }
                priorBytes += finalSizeOf(item, target)
                onProgress(index + 1, items.size)
            }
            importer.import(destination.absolutePath)
        }

    // Null (rather than a partial sum) the moment any item's size is unknown — a partial total
    // would silently under-report how much there is left to fetch.
    private fun totalBytesOrNull(items: List<HfDownloadItem>): Long? {
        val sizes = items.map { it.sizeBytes }
        if (sizes.any { it == null }) return null
        return sizes.sumOf { it ?: 0L }
    }

    // Once a file is done, its contribution to the running total is its advertised size if known,
    // else whatever actually landed on disk (still exact — just not knowable in advance).
    private fun finalSizeOf(
        item: HfDownloadItem,
        target: File,
    ): Long = item.sizeBytes ?: if (target.isFile) target.length() else 0L

    // Resume-aware, retrying fetch of one file: skip if already complete, else (re)try a download
    // that resumes from whatever is currently on disk, backing off between attempts. The
    // resume/skip/restart decision is the tested core policy — re-consulted before EVERY attempt
    // (including retries), since a dropped connection can leave more bytes on disk than the
    // previous attempt started with.
    private suspend fun fetch(
        item: HfDownloadItem,
        target: File,
        reportWithinFile: (Long) -> Unit,
    ) {
        requireFitsStorage(item)
        var attempt = 1
        while (true) {
            val onDisk = if (target.isFile) target.length() else 0L
            val decision = HfResume.decide(onDisk, item.sizeBytes)
            if (decision.action == ResumeAction.SKIP) {
                reportWithinFile(onDisk)
                return
            }
            val resumeFrom = if (decision.action == ResumeAction.RESUME) decision.offsetBytes else 0L
            reportWithinFile(resumeFrom)
            val failure = attemptDownload(item, target, resumeFrom, reportWithinFile) ?: return
            val giveUp = attempt >= MAX_ATTEMPTS || !isRetryable(failure)
            if (giveUp) throw describedFailure(item, failure, attempt)
            delay(RETRY_BACKOFF_MS[attempt - 1])
            attempt++
        }
    }

    // Fail fast (before spending bandwidth) when a file's advertised size can't fit free storage —
    // an upfront, clearer version of copyCapped's streaming guard. Unknown sizes fall through to it.
    private fun requireFitsStorage(item: HfDownloadItem) {
        if (!DownloadStorageCap.exceedsFreeStorage(item.sizeBytes, freeBytes())) return
        throw describedFailure(
            item,
            DownloadCapExceededException("not enough free storage (needs ${item.sizeBytes} bytes, ${freeBytes()} free)"),
            attempts = 1,
        )
    }

    // Runs one download attempt and returns the [IOException] it failed with, or null on success —
    // a return value rather than a caught-and-rethrown exception so [fetch]'s retry loop stays a
    // flat while-loop instead of nesting a try/catch inside it (never-nesting).
    private suspend fun attemptDownload(
        item: HfDownloadItem,
        target: File,
        resumeFrom: Long,
        reportWithinFile: (Long) -> Unit,
    ): IOException? =
        try {
            downloadFile(item.url, target, resumeFrom, reportWithinFile)
            null
        } catch (e: IOException) {
            e
        }

    private suspend fun downloadFile(
        url: String,
        target: File,
        resumeFrom: Long,
        reportWithinFile: (Long) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val connection = openConnection(url, resumeFrom)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in HTTP_SUCCESS_RANGE) {
                throw HttpStatusException(responseCode, connection.responseMessage)
            }
            // If we asked to resume but the server sent a full body (200, not 206 Partial Content),
            // it ignored the Range header — so overwrite from the start rather than append onto a
            // prefix that would then be duplicated.
            val serverResumed = resumeFrom > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            val startOffset = if (serverResumed) resumeFrom else 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(target, serverResumed).use { output ->
                    copyCapped(input, output, startOffset, DownloadStorageCap.capFor(freeBytes()), reportWithinFile)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // A permanent client error (404 the file doesn't exist, 403 access denied) is never worth
    // retrying — nothing about waiting and asking again will change the answer. Everything else
    // (a 5xx from an overloaded/flaky edge, or a connection-level IOException such as "Software
    // caused connection abort" / "unexpected end of stream" / a socket reset / a premature EOF) is
    // treated as transient. [DownloadCapExceededException] is likewise permanent: retrying a file
    // that is genuinely larger than the cap cannot succeed.
    private fun isRetryable(e: IOException): Boolean =
        when (e) {
            is DownloadCapExceededException -> false
            is HttpStatusException -> e.code !in NON_RETRYABLE_HTTP_CODES
            else -> true
        }

    // Always names the FILE and the REASON — never a bare URL (the sesame/csm-1b bug this fixes:
    // its surfaced "error" used to be exactly a `resolve/main/...` URL with no context).
    private fun describedFailure(
        item: HfDownloadItem,
        cause: IOException,
        attempts: Int,
    ): IOException {
        val raw = cause.message?.takeIf { it.isNotBlank() } ?: cause::class.simpleName ?: "unknown error"
        // A DNS/no-route failure (e.g. "Unable to resolve host …") reads as a plain "No internet
        // connection" line rather than the raw resolver text — a user drops off Wi-Fi far more often
        // than a repo file genuinely vanishes. A specific server/file error is left untouched.
        val reason = OfflineErrorHint.humanize(raw)
        val attemptsNote = if (attempts > 1) " (gave up after $attempts attempts)" else ""
        return IOException("Couldn't download ${item.relativePath}: $reason$attemptsNote", cause)
    }

    private fun openConnection(
        url: String,
        resumeFrom: Long,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true // resolve URLs redirect to the CDN
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            userAgent.forEach { (key, value) -> setRequestProperty(key, value) }
            if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
        }

    // Stream to disk with a hard byte cap so a malicious/misconfigured repo can't fill storage, and
    // a cooperative cancellation check each buffer so cancelling the coroutine stops the download
    // promptly (the partial file is intentionally left on disk to enable a later resume).
    // [reportWithinFile] is throttled to every BYTES_PROGRESS_STEP bytes (plus a final flush) so a
    // large weight file doesn't flood the caller with a callback every 8 KB buffer.
    private suspend fun copyCapped(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        startOffset: Long,
        cap: Long,
        reportWithinFile: (Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = startOffset
        var sinceReport = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            coroutineContext.ensureActive()
            total += read
            sinceReport += read
            if (total > cap) throw DownloadCapExceededException("not enough free storage to finish this download")
            output.write(buffer, 0, read)
            if (sinceReport >= BYTES_PROGRESS_STEP) {
                reportWithinFile(total)
                sinceReport = 0L
            }
            read = input.read(buffer)
        }
        reportWithinFile(total) // final flush: the caller must see the fully-written size, not a stale throttled one
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 8192
        private const val BYTES_PROGRESS_STEP = 256L * 1024 // throttle progress callbacks to every 256 KB
        private const val DIAGNOSTICS_LOG_FILENAME = "hf_download_diagnostics.json"

        // 1 initial attempt + up to 3 retries, backing off ~1s/2s/4s between them so a resumable
        // partial file has time for a flaky connection/CDN edge to recover before trying again.
        private const val MAX_ATTEMPTS = 4
        private val RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)
        private val HTTP_SUCCESS_RANGE = HttpURLConnection.HTTP_OK..HttpURLConnection.HTTP_PARTIAL

        // 404 the file genuinely doesn't exist, 403 access is denied — waiting and retrying changes
        // neither answer. Any other status (a 5xx from an overloaded/flaky edge, 429 rate limiting,
        // ...) is treated as transient and retried.
        private val NON_RETRYABLE_HTTP_CODES = setOf(HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_FORBIDDEN)
    }
}

/** A non-2xx HTTP response for a repo file fetch. [code] drives the retry decision (404/403 are
 * permanent — see [HfDownloader.isRetryable] — anything else, e.g. a 5xx, is treated as transient). */
private class HttpStatusException(val code: Int, statusMessage: String?) :
    IOException("HTTP $code${statusMessage?.let { " $it" } ?: ""}")

/** The download can't fit in free storage. Distinct from a generic [IOException] so it is never
 * retried — the file won't fit no matter how many times it's fetched. */
private class DownloadCapExceededException(message: String) : IOException(message)
