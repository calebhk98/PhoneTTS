package com.phonetts.app.ui.nav

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phonetts.app.AppGraph
import com.phonetts.app.compare.CompareScreen
import com.phonetts.app.compare.CompareViewModel
import com.phonetts.app.device.DeviceInfo
import com.phonetts.app.hf.DownloadNotifier
import com.phonetts.app.hf.HfBrowseScreen
import com.phonetts.app.hf.HfBrowseViewModel
import com.phonetts.app.manage.ModelManagementScreen
import com.phonetts.app.manage.ModelManagementViewModel
import com.phonetts.app.ui.MixVoicesScreen
import com.phonetts.app.ui.MixVoicesViewModel
import com.phonetts.app.ui.TtsViewModel

/**
 * The Voices hub (bottom-nav destination #3): one place to answer "which voice do I use?", instead
 * of Browse/Downloaded/Mix/Compare each being a separate top-level drawer destination. A
 * [SecondaryTabRow] switches between the four - each tab keeps exactly the ViewModel wiring (and
 * the "leaving this tab" side effects: `refreshModels()`/`stop()`) that MainActivity's old
 * per-[com.phonetts.app.Screen] drawer branches used to run on navigate-away, now run from a
 * [DisposableEffect] when a tab's content leaves composition (switching tabs, or leaving the hub
 * for another bottom destination, both dispose it the same way).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesHubScreen(
    graph: AppGraph,
    ttsViewModel: TtsViewModel,
    selectedTab: VoicesTab,
    onSelectTab: (VoicesTab) -> Unit,
) {
    SimpleScaffold(title = "Voices") {
        Column(Modifier.fillMaxSize()) {
            SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                VoicesTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    VoicesTab.BROWSE -> BrowseTabContent(graph, ttsViewModel)
                    VoicesTab.DOWNLOADED -> DownloadedTabContent(graph, ttsViewModel, onSelectTab)
                    VoicesTab.MIX -> MixTabContent(graph, ttsViewModel)
                    VoicesTab.COMPARE -> CompareTabContent(graph, ttsViewModel)
                }
            }
        }
    }
}

/** Same [HfBrowseViewModel] wiring MainActivity's old Screen.BROWSE branch built, including the
 * auto-resume-on-connectivity hookup (issue #105); `refreshModels()` on leaving so a download picked
 * up here shows up in the model dropdown and Downloaded tab right away (unchanged from before). */
@Composable
private fun BrowseTabContent(graph: AppGraph, ttsViewModel: TtsViewModel) {
    val hfViewModel: HfBrowseViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        HfBrowseViewModel(
                            graph.hfCatalog,
                            graph.hfDownloader,
                            graph.catalog,
                            isRuntimeAvailable = { id -> graph.runtimeRegistry.get(id)?.isAvailable() == true },
                            notifier = DownloadNotifier(graph.appContext),
                            benchmarkHistory = graph.benchmarkHistory,
                            knownEngineIds = graph.engineRegistry.list().map { it.id },
                        )
                    }
                },
        )
    DisposableEffect(hfViewModel) {
        val connectivity = graph.appContext.getSystemService(ConnectivityManager::class.java)
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = hfViewModel.onConnectivityAvailable()
            }
        connectivity?.registerDefaultNetworkCallback(callback)
        onDispose {
            connectivity?.unregisterNetworkCallback(callback)
            ttsViewModel.refreshModels()
        }
    }
    HfBrowseScreen(hfViewModel)
}

/** Same [ModelManagementViewModel] wiring MainActivity's old Screen.MANAGE branch built. The
 * "Add model" FAB's "Browse Hugging Face" choice just switches this hub to the Browse tab; "Import
 * from folder" still routes through [TtsViewModel.sideloadFolder] like the old drawer action did. */
@Composable
private fun DownloadedTabContent(
    graph: AppGraph,
    ttsViewModel: TtsViewModel,
    onSelectTab: (VoicesTab) -> Unit,
) {
    val manageViewModel: ModelManagementViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        ModelManagementViewModel(
                            modelManager = graph.modelManager,
                            resourceUsage = graph.resourceUsageStore,
                            // TOTAL ram, not free - the only figure that decides whether a model can
                            // physically fit (DeviceRamFit).
                            availableRamBytes = graph::availableRamBytes,
                            totalRamBytes = { DeviceInfo.totalRamBytes(graph.appContext) },
                            benchmarkHistory = graph.benchmarkHistory,
                            deviceName = graph.deviceName,
                        )
                    }
                },
        )
    DisposableEffect(manageViewModel) { onDispose { ttsViewModel.refreshModels() } }
    ModelManagementScreen(
        viewModel = manageViewModel,
        onBrowseHuggingFace = { onSelectTab(VoicesTab.BROWSE) },
        onImportFolder = ttsViewModel::sideloadFolder,
    )
}

/** Same [MixVoicesViewModel] wiring MainActivity's old Screen.MIX branch built - keyed off the
 * whole catalog rather than the main screen's current selection (issue #11), so it works even
 * before anything is selected on Home. */
@Composable
private fun MixTabContent(graph: AppGraph, ttsViewModel: TtsViewModel) {
    val ttsState by ttsViewModel.state.collectAsState()
    val mixViewModel: MixVoicesViewModel =
        viewModel(factory = viewModelFactory { initializer { MixVoicesViewModel(graph, ttsState.selected) } })
    DisposableEffect(mixViewModel) { onDispose { ttsViewModel.refreshModels() } }
    MixVoicesScreen(mixViewModel)
}

/** Same [CompareViewModel] wiring MainActivity's old Screen.COMPARE branch built; stops playback on
 * leaving the tab exactly like the old drawer's onNavigate used to. */
@Composable
private fun CompareTabContent(graph: AppGraph, ttsViewModel: TtsViewModel) {
    val ttsState by ttsViewModel.state.collectAsState()
    val compareViewModel: CompareViewModel =
        viewModel(factory = viewModelFactory { initializer { CompareViewModel(graph, ttsState.text) } })
    DisposableEffect(compareViewModel) { onDispose { compareViewModel.stop() } }
    CompareScreen(compareViewModel)
}
