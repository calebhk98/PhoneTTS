package com.phonetts.app.update

import com.phonetts.core.download.Sha256Verifier
import com.phonetts.core.update.ApkDownloadState
import com.phonetts.core.update.ApkIntegrity
import com.phonetts.core.update.UpdateStatus
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
 * Downloads the update APK **inside the app**, with visible progress, instead of handing the URL to
 * the browser.
 *
 * Why this exists: the Download button used to fire an `ACTION_VIEW` intent at the release asset URL
 * and that was the end of the app's involvement. GitHub answers
 * `github.com/<owner>/<repo>/releases/download/<tag>/<file>.apk` with a **302 to a signed,
 * short-lived** `release-assets.githubusercontent.com` URL (its `se=`/`ske=` signature window is
 * under an hour). A browser that queues the 85 MB transfer, pauses it, or retries it after that
 * window gets a 403 on the signed link and parks in "waiting"/"pending" forever - the reported "it
 * never finishes". Re-opening the release page works because each click mints a *fresh* signature.
 *
 * Fetching it ourselves fixes that at the root: every attempt re-requests the stable `github.com`
 * URL, so each attempt (including every retry) follows a freshly-minted redirect and never reuses an
 * expired signature. The bytes are streamed to disk (an 85 MB APK is never held in memory), the
 * total comes from the release's own advertised size, and the file is hashed against the release's
 * `sha256` digest before it is ever offered for install (fail-closed: a mismatch deletes the file).
 *
 * The file lands in `cacheDir/updates` - shareable to the system installer via the app's
 * FileProvider, and reclaimable by the OS if space runs short.
 */
class ApkUpdateDownloader(
    cacheDir: File,
    private val userAgent: Map<String, String> = UpdateHttp.USER_AGENT,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    private val updatesDir = File(cacheDir, UPDATES_DIR_NAME)

    /**
     * Fetch [status]'s APK, reporting progress through [onState]. Returns the terminal state
     * ([ApkDownloadState.Ready] or [ApkDownloadState.Failed]); the same value is also reported to
     * [onState], so a caller can just mirror the callback into its UI state.
     *
     * Cancelling the calling coroutine abandons the download and leaves no partial file behind.
     */
    suspend fun download(
        status: UpdateStatus,
        onState: (ApkDownloadState) -> Unit,
    ): ApkDownloadState =
        withContext(Dispatchers.IO) {
            val url = status.apkDownloadUrl ?: return@withContext fail("This release has no downloadable APK", onState)
            val target = File(updatesDir, fileNameFor(status))
            runCatching { fetchAndVerify(url, target, status, onState) }
                .getOrElse { error ->
                    // Whether this was a failure or a cancellation, leave no half-written file behind
                    // (an abandoned 85 MB APK in the cache helps nobody).
                    discardPartials()
                    coroutineContext.ensureActive() // a cancellation is not a failure to report
                    fail(reasonFor(error), onState)
                }
        }

    /** Delete any APKs left in the cache (after a successful install, or when the offer is dismissed). */
    fun clear() {
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    private fun discardPartials() {
        updatesDir.listFiles()?.filter { it.name.endsWith(PARTIAL_SUFFIX) }?.forEach { it.delete() }
    }

    private suspend fun fetchAndVerify(
        url: String,
        target: File,
        status: UpdateStatus,
        onState: (ApkDownloadState) -> Unit,
    ): ApkDownloadState {
        updatesDir.mkdirs()
        // Only ever keep the APK we're fetching now - a stale one from a previous offer is dead weight.
        updatesDir.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }

        val partial = File(updatesDir, target.name + PARTIAL_SUFFIX)
        partial.delete()
        onState(ApkDownloadState.Downloading(bytesDone = 0, bytesTotal = status.apkSizeBytes))
        val written = downloadWithRetries(url, partial, status.apkSizeBytes, onState)
        if (written <= 0) {
            partial.delete()
            return fail("Download produced no data - try again", onState)
        }

        onState(ApkDownloadState.Verifying)
        val actual = partial.inputStream().use { Sha256Verifier.hashHex(it) }
        val verdict = ApkIntegrity.check(status.apkSha256, actual)
        if (verdict == ApkIntegrity.Verdict.MISMATCH) {
            partial.delete()
            return fail("Downloaded file didn't match the release's checksum - not installing it", onState)
        }
        target.delete()
        if (!partial.renameTo(target)) return fail("Couldn't finalize the downloaded file", onState)

        val ready = ApkDownloadState.Ready(target.absolutePath, verified = verdict == ApkIntegrity.Verdict.VERIFIED)
        onState(ready)
        return ready
    }

    // Retry a dropped connection - common on mobile networks, and cheap here because every attempt
    // re-requests the stable github.com URL and so follows a NEWLY signed redirect (the expiry that
    // strands browser downloads can't strand this).
    private suspend fun downloadWithRetries(
        url: String,
        partial: File,
        advertisedTotal: Long?,
        onState: (ApkDownloadState) -> Unit,
    ): Long {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            val written = runCatching { streamOnce(url, partial, advertisedTotal, onState) }
            written.getOrNull()?.let { return it }
            val error = written.exceptionOrNull()
            coroutineContext.ensureActive()
            if (error !is IOException) throw (error ?: IOException("download failed"))
            lastError = error
            partial.delete()
            delay(BACKOFF_BASE_MS shl attempt)
        }
        throw lastError ?: IOException("download failed")
    }

    private suspend fun streamOnce(
        url: String,
        partial: File,
        advertisedTotal: Long?,
        onState: (ApkDownloadState) -> Unit,
    ): Long {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in HTTP_OK_RANGE) throw IOException("HTTP $code")
            // Prefer the release's advertised size; fall back to what this response declares.
            val total = advertisedTotal ?: connection.contentLengthLong.takeIf { it > 0 }
            return connection.inputStream.use { source ->
                partial.outputStream().use { sink ->
                    copyReporting(source, sink, total, onState)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun copyReporting(
        source: java.io.InputStream,
        sink: java.io.OutputStream,
        total: Long?,
        onState: (ApkDownloadState) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var done = 0L
        var reportedAt = 0L
        var read = source.read(buffer)
        while (read >= 0) {
            coroutineContext.ensureActive()
            sink.write(buffer, 0, read)
            done += read
            if (done - reportedAt >= PROGRESS_STEP_BYTES) {
                reportedAt = done
                onState(ApkDownloadState.Downloading(done, total))
            }
            read = source.read(buffer)
        }
        onState(ApkDownloadState.Downloading(done, total))
        return done
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            // GitHub's asset URL is a 302 to a signed CDN link; following it is the whole point.
            instanceFollowRedirects = true
            userAgent.forEach { (key, value) -> setRequestProperty(key, value) }
        }

    // Name the file what the release named it, but never let a remote string escape the updates dir.
    private fun fileNameFor(status: UpdateStatus): String {
        val advertised = status.apkFileName?.substringAfterLast('/')?.substringAfterLast('\\')
        val safe = advertised?.takeIf { it.isNotBlank() && it != "." && it != ".." && it.endsWith(".apk", true) }
        return safe ?: "phonetts-${status.latestVersion}.apk"
    }

    private fun reasonFor(error: Throwable): String =
        when (error) {
            is IOException -> "Download failed: ${error.message ?: "connection lost"}"
            else -> "Download failed: ${error.message ?: error::class.simpleName}"
        }

    private fun fail(
        reason: String,
        onState: (ApkDownloadState) -> Unit,
    ): ApkDownloadState = ApkDownloadState.Failed(reason).also(onState)

    companion object {
        private const val UPDATES_DIR_NAME = "updates"
        private const val PARTIAL_SUFFIX = ".part"
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024
        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_BASE_MS = 1_000L
        private val HTTP_OK_RANGE = 200..299
    }
}

/** Shared HTTP identity for the update path (the check and the download speak as the same client). */
object UpdateHttp {
    val USER_AGENT = mapOf("User-Agent" to "PhoneTTS (offline-tts-android; update-download)")
}
