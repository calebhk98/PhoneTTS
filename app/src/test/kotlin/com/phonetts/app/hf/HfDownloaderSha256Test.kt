package com.phonetts.app.hf

import com.phonetts.core.download.Sha256Verifier
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
 * Rule 7 / rule 4 (fail closed): a curated [com.phonetts.core.download.builtin.BuiltInModel]'s
 * pinned [HfDownloadItem.expectedSha256] must actually gate whether the downloaded file is trusted
 * - a matching hash is accepted, a mismatch is rejected AND the corrupted/tampered file is deleted
 * (never silently loaded), and a `null` hash (every browsed-repo item; see the class kdoc on
 * [HfDownloader]) is honestly treated as "not verified" rather than an implicit pass.
 *
 * Uses a tiny real in-process HTTP server (a bare [ServerSocket] speaking just enough HTTP/1.1 to
 * serve one fixed-body 200 response - no `jdk.httpserver`/mocking framework needed) so the test
 * exercises the actual `HttpURLConnection` download path, not a mocked transport.
 */
class HfDownloaderSha256Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TinyHttpServer
    private val fileBytes = "the quick brown fox jumps over the lazy dog".toByteArray()
    private val realHash = Sha256Verifier.hashHex(fileBytes)

    @Before
    fun startServer() {
        server = TinyHttpServer(fileBytes)
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun url(): String = "http://127.0.0.1:${server.port}/model.bin"

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

    // Not run under Robolectric (no need - none of this exercises real Android framework code), so
    // android.os.StatFs (HfDownloader's default freeBytes source) is an unstubbed no-op that would
    // report 0 free bytes and trip the storage cap for reasons unrelated to what these tests are
    // actually about - a generous fixed value here isolates that.
    private fun downloader(): HfDownloader =
        HfDownloader(tmp.newFolder("private"), claimingImporter(), freeBytes = { 1_000_000_000L })

    @Test
    fun `a download whose bytes match the pinned hash is accepted`() =
        runBlocking {
            val items = listOf(HfDownloadItem(url = url(), relativePath = "model.bin", sizeBytes = null, expectedSha256 = realHash))

            val descriptor = downloader().downloadAndImport("good-model", items)

            assertEquals("good-model", descriptor.modelId)
        }

    @Test
    fun `a download whose bytes do not match the pinned hash is rejected and the file is deleted`() =
        runBlocking {
            val wrongHash = realHash.replaceFirst(realHash[0], if (realHash[0] == 'a') 'b' else 'a')
            val items =
                listOf(HfDownloadItem(url = url(), relativePath = "model.bin", sizeBytes = null, expectedSha256 = wrongHash))

            val error =
                try {
                    downloader().downloadAndImport("bad-model", items)
                    null
                } catch (e: IOException) {
                    e
                }

            assertTrue("a hash mismatch must throw, not silently import", error != null)
            val target = File(tmp.root, "private/models/bad-model/model.bin")
            assertFalse("the corrupted/mismatched file must be deleted, not left on disk", target.exists())
        }

    @Test
    fun `a null expected hash is tolerated - the download proceeds without pretending to verify`() =
        runBlocking {
            val items = listOf(HfDownloadItem(url = url(), relativePath = "model.bin", sizeBytes = null, expectedSha256 = null))

            val descriptor = downloader().downloadAndImport("unverified-model", items)

            assertEquals("unverified-model", descriptor.modelId)
            val target = File(tmp.root, "private/models/unverified-model/model.bin")
            assertTrue(target.exists())
        }
}

/**
 * The smallest possible HTTP/1.1 server that can stand in for a Hugging Face file download in a
 * test: accepts one connection at a time, ignores the request entirely, and always answers with a
 * fixed 200 body. Deliberately built on a bare [ServerSocket] (`java.net`, always present) rather
 * than `com.sun.net.httpserver.HttpServer` (the `jdk.httpserver` module, not resolvable from this
 * module's unit-test compile classpath).
 */
private class TinyHttpServer(private val bodyBytes: ByteArray) : AutoCloseable {
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
        socket.getInputStream().bufferedReader().let { reader ->
            // Drain the request line + headers up to the blank line; content doesn't matter here.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
        }
        socket.getOutputStream().use { out ->
            val header = "HTTP/1.1 200 OK\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(bodyBytes)
            out.flush()
        }
    }

    override fun close() {
        serverSocket.close()
        thread.join(1_000)
    }
}
