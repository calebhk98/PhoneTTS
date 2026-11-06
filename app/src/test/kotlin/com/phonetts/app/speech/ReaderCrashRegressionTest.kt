package com.phonetts.app.speech

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.PhoneTtsApplication
import com.phonetts.app.playback.GenerationNotifier
import com.phonetts.core.testing.FakeEngine
import com.phonetts.core.testing.testDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderCrashRegressionTest {
    @Test
    fun browseDeclaresPermissionAndCanRegisterNetworkCallback() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val declared =
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.ACCESS_NETWORK_STATE in declared)
        // Simulate Android's install-time grant; Robolectric leaves normal permissions denied.
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {}
        connectivity.registerDefaultNetworkCallback(callback)
        connectivity.unregisterNetworkCallback(callback)
    }

    @Test
    fun readerSpillsAndCleansUpWithLongDocumentModeOff() = checkReaderSpill(false)

    @Test
    fun readerSpillsAndCleansUpWithLongDocumentModeOn() = checkReaderSpill(true)

    private fun checkReaderSpill(longDocumentMode: Boolean) =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<PhoneTtsApplication>()
            val graph = app.graph
            val descriptor = testDescriptor("spill-test", "spill-test", sampleRate = 100)
            val chunks = List(14) { FloatArray(1_000) }
            graph.engineRegistry.register(FakeEngine(id = descriptor.engineId, audio = chunks))
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller = ReaderPlaybackController(graph, SpeechPlayer(), owner, GenerationNotifier(app))
            val existing = app.cacheDir.listFiles().orEmpty().toSet()
            try {
                controller.generateOnly(
                    ReaderPlaybackController.Selection(descriptor, "v0", "Test.", emptyMap(), longDocumentMode),
                    "spill-test-document",
                    onEngineLoaded = {},
                    onModelLoaded = {},
                )
                withTimeout(10_000) {
                    controller.state.first { it.sentencesReady == chunks.size && !it.generating }
                }
                val spill =
                    app.cacheDir.listFiles().orEmpty().filter {
                        it !in existing && it.name.startsWith("phonetts_spill_")
                    }
                assertEquals(1, spill.size)
                assertTrue("audio must spill even with the preference disabled", spill.single().length() > 0)
                controller.abandon(null, null, 1f, null)
                withTimeout(10_000) {
                    while (spill.single().exists()) kotlinx.coroutines.delay(10)
                }
            } finally {
                controller.abandon(null, null, 1f, null)
                owner.cancel()
                graph.engineManager.unloadCurrent()
                graph.engineRegistry.unregister(descriptor.engineId)
            }
        }
}
