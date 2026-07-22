package com.phonetts.app.hf

import com.phonetts.app.ModelStorage
import com.phonetts.core.download.SafePath
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.download.hf.HfDownloadItem
import com.phonetts.core.download.hf.HfResume
import com.phonetts.core.download.hf.ResumeAction
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.sideload.ModelImporter
import kotlinx.coroutines.Dispatchers
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
 */
class HfDownloader(
    private val filesDir: File,
    private val importer: ModelImporter,
    private val userAgent: Map<String, String> = HfCatalog.USER_AGENT,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
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

    // Resume-aware fetch of one file: skip if already complete, resume from the on-disk offset if
    // partial, else download fresh. The resume/skip/restart decision is the tested core policy.
    private suspend fun fetch(
        item: HfDownloadItem,
        target: File,
        reportWithinFile: (Long) -> Unit,
    ) {
        val onDisk = if (target.isFile) target.length() else 0L
        val decision = HfResume.decide(onDisk, item.sizeBytes)
        if (decision.action == ResumeAction.SKIP) {
            reportWithinFile(onDisk)
            return
        }
        val resumeFrom = if (decision.action == ResumeAction.RESUME) decision.offsetBytes else 0L
        reportWithinFile(resumeFrom)
        downloadFile(item.url, target, resumeFrom, reportWithinFile)
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
            // If we asked to resume but the server sent a full body (200, not 206 Partial Content),
            // it ignored the Range header — so overwrite from the start rather than append onto a
            // prefix that would then be duplicated.
            val serverResumed = resumeFrom > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val startOffset = if (serverResumed) resumeFrom else 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(target, serverResumed).use { output ->
                    copyCapped(input, output, startOffset, reportWithinFile)
                }
            }
        } finally {
            connection.disconnect()
        }
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
            if (total > maxFileBytes) throw IOException("download exceeds $maxFileBytes bytes cap")
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
        private const val DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB per file
        private const val BUFFER_SIZE = 8192
        private const val BYTES_PROGRESS_STEP = 256L * 1024 // throttle progress callbacks to every 256 KB
    }
}
