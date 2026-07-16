package com.phonetts.app.hf

import com.phonetts.app.ModelStorage
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.download.hf.HfDownloadItem
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.sideload.ModelImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a Hugging Face repo's files into app-private storage, then hands the folder to the
 * tested core [ModelImporter] — which reads it, resolves it (auto-detect that fails closed, else
 * the user pick), and catalogs it. So a browsed model becomes usable with NO engine-specific code.
 *
 * Weights are streamed to disk (never held in memory). HF ETags are not stable content hashes, so
 * there is no manifest hash to check against for an arbitrary repo; integrity for browsed models
 * rests on HTTPS + the resolver refusing anything it can't identify. (Curated/manifest downloads
 * still verify SHA-256 via com.phonetts.core.download.Sha256Verifier.)
 */
class HfDownloader(
    private val filesDir: File,
    private val importer: ModelImporter,
    private val userAgent: Map<String, String> = HfCatalog.USER_AGENT,
) {
    suspend fun downloadAndImport(
        modelId: String,
        items: List<HfDownloadItem>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ModelDescriptor =
        withContext(Dispatchers.IO) {
            val destination = ModelStorage.modelDir(filesDir, modelId)
            destination.mkdirs()
            items.forEachIndexed { index, item ->
                downloadFile(item.url, File(destination, item.relativePath))
                onProgress(index + 1, items.size)
            }
            importer.import(destination.absolutePath)
        }

    private fun downloadFile(url: String, target: File) {
        target.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true // resolve URLs redirect to the CDN
            userAgent.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
    }

}
