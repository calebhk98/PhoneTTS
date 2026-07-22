package com.phonetts.app

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phonetts.app.playback.PlaybackService
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phonetts.app.benchmark.BenchmarkScreen
import com.phonetts.app.benchmark.BenchmarkViewModel
import com.phonetts.app.hf.HfBrowseScreen
import com.phonetts.app.hf.HfBrowseViewModel
import com.phonetts.app.manage.ModelManagementScreen
import com.phonetts.app.manage.ModelManagementViewModel
import com.phonetts.app.ui.HelpScreen
import com.phonetts.app.ui.MixVoicesScreen
import com.phonetts.app.ui.MixVoicesViewModel
import com.phonetts.app.ui.OnboardingScreen
import com.phonetts.app.ui.SleepTimerHandle
import com.phonetts.app.ui.TtsScreen
import com.phonetts.app.ui.TtsViewModel
import com.phonetts.app.ui.theme.PhoneTtsTheme
import com.phonetts.core.prefs.AppTheme

private enum class Screen { ONBOARDING, MAIN, BROWSE, MANAGE, BENCHMARK, HELP, MIX }

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as PhoneTtsApplication).graph }
    private val ttsViewModel: TtsViewModel by viewModels {
        viewModelFactory { initializer { TtsViewModel(graph) } }
    }
    // A Compose State (not a plain var) so AppNav recomposes — and rebuilds the SleepTimerHandle
    // it hands TtsScreen — the moment the service connects/disconnects.
    private val binderState = mutableStateOf<PlaybackService.LocalBinder?>(null)

    // Bind to the foreground playback service so its notification/lock-screen Play/Pause/Stop
    // reach the same TtsViewModel controls, and forward every playback-state change back to it so
    // the notification stays in sync. Bound only while the activity is started.
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val localBinder = service as? PlaybackService.LocalBinder ?: return
                binderState.value = localBinder
                localBinder.attachController(ttsViewModel.playbackController)
                // In-app Stop bypasses the service, so route it a "this stop is user-initiated"
                // signal, otherwise a manual Stop reads as natural completion and chimes (issue #32).
                ttsViewModel.onUserStopRequested = { localBinder.notifyUserStop() }
                forwardState(localBinder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                ttsViewModel.onUserStopRequested = null
                binderState.value = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedText = intent?.readSharedText()
        setContent {
            // Theme choice is hoisted above PhoneTtsTheme so a pick in the picker recomposes the
            // whole tree; persisted through AppThemePreference so it survives relaunch.
            var theme by remember { mutableStateOf(graph.appThemePreference.selected()) }
            PhoneTtsTheme(theme = theme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav(
                        graph = graph,
                        ttsViewModel = ttsViewModel,
                        sharedText = sharedText,
                        binderState = binderState,
                        theme = theme,
                        onThemeSelected = { chosen ->
                            theme = chosen
                            graph.appThemePreference.select(chosen)
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        ttsViewModel.onUserStopRequested = null
        binderState.value?.detachController()
        unbindService(connection)
        binderState.value = null
    }

    private fun forwardState(localBinder: PlaybackService.LocalBinder) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ttsViewModel.state.collect { s ->
                    localBinder.onStateChanged(s.playing, s.paused, s.selected?.displayName)
                }
            }
        }
    }
}

// Text handed to us by "Share to PhoneTTS" (ACTION_SEND) or a "read aloud" text-selection action
// (ACTION_PROCESS_TEXT). Null for a normal launch.
private fun Intent.readSharedText(): String? =
    when (action) {
        Intent.ACTION_SEND -> getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }?.takeIf { it.isNotBlank() }

// Adapts the (possibly still-unbound) PlaybackService.LocalBinder to the small SleepTimerHandle
// facade TtsScreen depends on, so the UI layer never needs to know about service binding.
private fun PlaybackService.LocalBinder?.toSleepTimerHandle(): SleepTimerHandle {
    val binder = this
    return object : SleepTimerHandle {
        override fun start(durationMillis: Long) {
            binder?.startSleepTimer(durationMillis)
        }

        override fun cancel() = binder?.cancelSleepTimer() ?: Unit

        override fun isRunning() = binder?.isSleepTimerRunning ?: false

        override fun remainingMillis() = binder?.sleepTimerRemainingMillis() ?: 0L
    }
}

@Composable
private fun AppNav(
    graph: AppGraph,
    ttsViewModel: TtsViewModel,
    sharedText: String?,
    binderState: State<PlaybackService.LocalBinder?>,
    theme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
) {
    // First run lands on the walkthrough; every launch after it has been dismissed goes straight
    // to the reader. remember{} reads the flag once so dismissing it mid-session sticks.
    var screen by remember {
        mutableStateOf(if (graph.onboardingState.hasSeenOnboarding()) Screen.MAIN else Screen.ONBOARDING)
    }
    val binder by binderState

    // Load shared/handed-in text into the reader once, when the activity was opened via a share.
    LaunchedEffect(sharedText) {
        sharedText?.let(ttsViewModel::setText)
    }

    when (screen) {
        Screen.ONBOARDING ->
            OnboardingScreen(
                onFinish = {
                    graph.onboardingState.markSeen()
                    screen = Screen.MAIN
                },
            )
        Screen.MAIN ->
            TtsScreen(
                viewModel = ttsViewModel,
                onBrowseModels = { screen = Screen.BROWSE },
                onManageModels = { screen = Screen.MANAGE },
                onBenchmarks = { screen = Screen.BENCHMARK },
                onHelp = { screen = Screen.HELP },
                onMixVoices = { screen = Screen.MIX },
                appVersion = BuildConfig.VERSION_NAME,
                sleepTimer = remember(binder) { binder.toSleepTimerHandle() },
            )
        Screen.BROWSE -> {
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
                                )
                            }
                        },
                )
            BackScaffold(title = "Browse models", onBack = {
                ttsViewModel.refreshModels() // pick up anything downloaded while browsing
                screen = Screen.MAIN
            }) { HfBrowseScreen(hfViewModel) }
        }
        Screen.MANAGE -> {
            val manageViewModel: ModelManagementViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                            initializer {
                                ModelManagementViewModel(
                                    modelManager = graph.modelManager,
                                    resourceUsage = graph.resourceUsageStore,
                                    availableRamBytes = graph::availableRamBytes,
                                )
                            }
                        },
                )
            BackScaffold(title = "Downloaded models", onBack = {
                ttsViewModel.refreshModels() // a delete removes it from the model dropdown too
                screen = Screen.MAIN
            }) { ModelManagementScreen(manageViewModel) }
        }
        Screen.BENCHMARK -> {
            val benchmarkViewModel: BenchmarkViewModel =
                viewModel(factory = viewModelFactory { initializer { BenchmarkViewModel(graph) } })
            BackScaffold(title = "Benchmarks", onBack = { screen = Screen.MAIN }) {
                BenchmarkScreen(benchmarkViewModel)
            }
        }
        Screen.MIX -> {
            val ttsState by ttsViewModel.state.collectAsState()
            val mixViewModel: MixVoicesViewModel =
                viewModel(
                    key = ttsState.selected?.modelId,
                    factory = viewModelFactory { initializer { MixVoicesViewModel(graph, ttsState.selected) } },
                )
            BackScaffold(title = "Mix voices", onBack = {
                ttsViewModel.refreshModels() // a saved mix may have become selectable
                screen = Screen.MAIN
            }) { MixVoicesScreen(mixViewModel) }
        }
        Screen.HELP -> {
            val ttsState by ttsViewModel.state.collectAsState()
            BackScaffold(title = "Help", onBack = { screen = Screen.MAIN }) {
                HelpScreen(
                    currentVersion = BuildConfig.VERSION_NAME,
                    update = ttsState.update,
                    checkStatus = ttsState.updateCheckStatus,
                    onCheckForUpdates = ttsViewModel::checkForUpdatesNow,
                    currentTheme = theme,
                    onThemeSelected = onThemeSelected,
                )
            }
        }
    }
}

// A Scaffold's topBar reserves and pads for the status bar itself, so this is also what keeps
// the back control (and every screen below it) clear of the notification/status bar area.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }
    }
}
