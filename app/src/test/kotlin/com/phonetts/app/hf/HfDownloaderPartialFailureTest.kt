package com.phonetts.app.hf

import com.phonetts.core.download.hf.DiagnosticsKind
import com.phonetts.core.download.hf.DownloadDiagnosticsLog
import com.phonetts.core.download.hf.HfDownloadItem
import com.phonetts.core.engine.EngineMatch
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.model.Origin
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.resolver.InMemoryOverrideStore
import com.phonetts.core.resolver.Resolver
import com.phonetts.core.sideload.DirectoryBundleReader
import com.phonetts.core.sideload.ModelImporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ServerSocket

/**
 * Issue #102: a single bad file must not abort the whole model download. Covers the three cases
 * the issue calls out explicitly:
 * - every file succeeds (baseline);
 * - a non-essential (companion) file fails - the import proceeds anyway and the skip is recorded
 *   to the durable [DownloadDiagnosticsLog], never silently dropped;
 * - an essential (model-weight) file fails - the import is refused outright, and the failure names
 *   the missing file, rather than either aborting opaquely or silently shipping a broken model.
 *
 * Uses the same bare-[ServerSocket] fake HTTP server idiom as [HfDownloaderSha256Test], extended to
 * route by request path so individual files in one "repo" can be made to succeed or 404.
 */
class HfDownloaderPartialFailureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: RoutedHttpServer

    @After
    fun stopServer() {
        if (::server.isInitialized) server.close()
    }

    private fun url(path: String): String = "http://127.0.0.1:${server.port}/$path"

    private fun claimingImporter(): ModelImporter {
        val engine =
            object : VoiceEngine {
                override val id = "fake"
                override val displayName = "Fake"

                override fun inspect(bundle: ModelBundle): EngineMatch =
                    EngineMatch(
                        id,
                        ModelDescriptor(
                            modelId = bundle.id,
                            engineId = id,
                            displayName = bundle.id,
                            origin = Origin.BUILT_IN,
                            sampleRate = 22_050,
                            voices = listOf(Voice("v0", "Voice 0", "en")),
                            defaultVoiceId = "v0",
                            parameters = emptyList<ModelParameter>(),
                        ),
                    )

                override fun forcedMatch(bundle: ModelBundle): EngineMatch = inspect(bundle)

                override suspend fun load(descriptor: ModelDescriptor) = Unit

                override fun unload() = Unit

                override fun voices(): List<Voice> = listOf(Voice("v0", "Voice 0", "en"))

                override fun synthesize(
                    text: String,
                    voiceId: String,
                    params: SynthesisParams,
                ): Flow<FloatArray> = flowOf(floatArrayOf(0f))
            }
        val resolver = Resolver(listOf(engine), InMemoryOverrideStore()) { error("should not be asked") }
        return ModelImporter(DirectoryBundleReader(), resolver, ModelCatalog())
    }

    // See HfDownloaderSha256Test's identical note: StatFs is unstubbed off-Robolectric, so freeBytes
    // is stubbed to a generous fixed value here to isolate these tests from that.
    private fun downloader(diagnosticsLog: DownloadDiagnosticsLog): HfDownloader =
        HfDownloader(
            filesDir = tmp.newFolder("private"),
            importer = claimingImporter(),
            freeBytes = { 1_000_000_000L },
            diagnosticsLog = diagnosticsLog,
        )

    @Test
    fun `every file succeeding imports normally`() =
        runBlocking {
            server = RoutedHttpServer(mapOf("model.onnx" to "weights".toByteArray(), "config.json" to "{}".toByteArray()))
            val diagnosticsLog = DownloadDiagnosticsLog(File(tmp.newFolder("diag-ok"), "log.json"))
            val items =
                listOf(
                    HfDownloadItem(url = url("model.onnx"), relativePath = "model.onnx", sizeBytes = null),
                    HfDownloadItem(url = url("config.json"), relativePath = "config.json", sizeBytes = null),
                )

            val descriptor = downloader(diagnosticsLog).downloadAndImport("good-model", items)

            assertEquals("good-model", descriptor.modelId)
            assertTrue(diagnosticsLog.entries().isEmpty())
        }

    @Test
    fun `a failing non-essential companion file is skipped, not fatal`() =
        runBlocking {
            // config.json 404s (permanent, non-retryable - keeps the test fast); the weight file is
            // fine.
            server = RoutedHttpServer(mapOf("model.onnx" to "weights".toByteArray()))
            val diagnosticsLog = DownloadDiagnosticsLog(File(tmp.newFolder("diag-skip"), "log.json"))
            val items =
                listOf(
                    HfDownloadItem(url = url("model.onnx"), relativePath = "model.onnx", sizeBytes = null),
                    HfDownloadItem(url = url("config.json"), relativePath = "config.json", sizeBytes = null),
                )

            val descriptor = downloader(diagnosticsLog).downloadAndImport("partial-model", items)

            assertEquals("import must proceed when only a companion file failed", "partial-model", descriptor.modelId)
            val target = File(tmp.root, "private/models/partial-model")
            assertTrue("the essential weight file must be on disk", File(target, "model.onnx").exists())
            assertFalse("the failed companion file must not exist", File(target, "config.json").exists())

            val entries = diagnosticsLog.entries()
            assertEquals("the skip must be durably recorded, not silently dropped", 1, entries.size)
            assertEquals(DiagnosticsKind.FAILURE, entries.single().kind)
            assertTrue(
                "the diagnostics entry must name the skipped file",
                entries.single().detail.contains("config.json"),
            )
        }

    @Test
    fun `a failing essential weight file refuses the whole import and names the file`() =
        runBlocking {
            // model.onnx 404s; config.json is fine - but a model with no weights is not usable.
            server = RoutedHttpServer(mapOf("config.json" to "{}".toByteArray()))
            val diagnosticsLog = DownloadDiagnosticsLog(File(tmp.newFolder("diag-fail"), "log.json"))
            val items =
                listOf(
                    HfDownloadItem(url = url("model.onnx"), relativePath = "model.onnx", sizeBytes = null),
                    HfDownloadItem(url = url("config.json"), relativePath = "config.json", sizeBytes = null),
                )

            val error =
                try {
                    downloader(diagnosticsLog).downloadAndImport("broken-model", items)
                    null
                } catch (e: IOException) {
                    e
                }

            assertTrue("a missing essential weight file must refuse the import", error != null)
            assertTrue(
                "the failure must name the missing weight file, never a bare/blank message",
                error!!.message?.contains("model.onnx") == true,
            )
        }
}

/**
 * Like [TinyHttpServer] in [HfDownloaderSha256Test] but routes by request path, so one server can
 * stand in for a multi-file HF repo where individual files succeed (200) or are missing (404).
 */
private class RoutedHttpServer(private val bodies: Map<String, ByteArray>) : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    private val thread =
        Thread {
            while (!serverSocket.isClosed) {
                val socket = acceptOrNull() ?: return@Thread
                socket.use { respond(it) }
            }
        }.apply {
            isDaemon = true
            start()
        }

    val port: Int get() = serverSocket.localPort

    private fun acceptOrNull(): java.net.Socket? = try { serverSocket.accept() } catch (e: IOException) { null }

    private fun respond(socket: java.net.Socket) {
        val requestLine =
            socket.getInputStream().bufferedReader().let { reader ->
                val first = reader.readLine() ?: ""
                while (true) {
                    val next = reader.readLine() ?: break
                    if (next.isEmpty()) break
                }
                first
            }
        val path = requestLine.split(" ").getOrNull(1)?.removePrefix("/") ?: ""
        val body = bodies[path]
        socket.getOutputStream().use { out ->
            if (body == null) {
                val message = "not found".toByteArray()
                out.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: ${message.size}\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                out.write(message)
            } else {
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                out.write(body)
            }
            out.flush()
        }
    }

    override fun close() {
        serverSocket.close()
        thread.join(1_000)
    }
}
