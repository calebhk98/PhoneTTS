package com.phonetts.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phonetts.app.playback.PlaybackService
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.phonetts.app.compare.CompareScreen
import com.phonetts.app.compare.CompareViewModel
import com.phonetts.app.device.DeviceInfo
import com.phonetts.app.hf.DownloadNotifier
import com.phonetts.app.hf.HfBrowseScreen
import com.phonetts.app.hf.HfBrowseViewModel
import com.phonetts.app.library.ReadingLibraryScreen
import com.phonetts.app.library.ReadingLibraryViewModel
import com.phonetts.app.manage.ModelManagementScreen
import com.phonetts.app.manage.ModelManagementViewModel
import com.phonetts.app.ui.HelpScreen
import com.phonetts.app.ui.MixVoicesScreen
import com.phonetts.app.ui.MixVoicesViewModel
import com.phonetts.app.ui.NavDrawerScaffold
import com.phonetts.app.ui.OnboardingScreen
import com.phonetts.app.ui.SleepTimerHandle
import com.phonetts.app.ui.TtsScreen
import com.phonetts.app.ui.TtsViewModel
import com.phonetts.app.ui.theme.PhoneTtsTheme
import com.phonetts.core.prefs.AppTheme

// Not private: shared with NavDrawerScaffold (app/ui), which every sub-page uses to list every
// destination in its own hamburger drawer (issue #1) — the same enum TtsScreen's drawer navigates.
enum class Screen { ONBOARDING, MAIN, BROWSE, MANAGE, BENCHMARK, HELP, MIX, LIBRARY, COMPARE }

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as PhoneTtsApplication).graph }
    private val ttsViewModel: TtsViewModel by viewModels {
        viewModelFactory { initializer { TtsViewModel(graph) } }
    }
    // A Compose State (not a plain var) so AppNav recomposes — and rebuilds the SleepTimerHandle
    // it hands TtsScreen — the moment the service connects/disconnects.
    private val binderState = mutableStateOf<PlaybackService.LocalBinder?>(null)

    // POST_NOTIFICATIONS is declared in the manifest, but Android 13+ (API 33) also requires a
    // RUNTIME grant — without it the system silently drops EVERY notification, so the download,
    // generation, and playback notifications this app already posts never appeared. Registered here
    // (before the activity is started, as the result API requires) and launched once from onCreate.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort: notifications are optional */ }

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
        ensureNotificationPermission()
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

    // Ask for POST_NOTIFICATIONS once on Android 13+ if it isn't already granted, so the download/
    // generation/playback notifications become visible. Best-effort: a denial just means no
    // notifications, never a broken app.
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                onLibrary = { screen = Screen.LIBRARY },
                onCompare = { screen = Screen.COMPARE },
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
                                    // Turns on the per-download progress notification (#70); null would
                                    // silently disable it. Uses the app context — no Activity leak.
                                    notifier = DownloadNotifier(graph.appContext),
                                    // Feeds the RTF sort/filter (issue: real-time-factor sort) from the
                                    // SAME persisted history the Benchmarks screen writes to — never a
                                    // second measurement path.
                                    benchmarkHistory = graph.benchmarkHistory,
                                )
                            }
                        },
                )
            BackScaffold(title = "Browse models", current = Screen.BROWSE, onNavigate = { target ->
                ttsViewModel.refreshModels() // pick up anything downloaded while browsing
                screen = target
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
                                    // TOTAL ram, not free — the only figure that decides whether a
                                    // model can physically fit (DeviceRamFit); AppGraph itself is
                                    // off-limits here, so this calls DeviceInfo directly off its
                                    // already-public appContext instead of adding a new AppGraph
                                    // method.
                                    totalRamBytes = { DeviceInfo.totalRamBytes(graph.appContext) },
                                    // Enables a MEASURED real-time factor per model (from past
                                    // benchmarks on this device) instead of only the estimate.
                                    benchmarkHistory = graph.benchmarkHistory,
                                    deviceName = graph.deviceName,
                                )
                            }
                        },
                )
            BackScaffold(title = "Downloaded models", current = Screen.MANAGE, onNavigate = { target ->
                ttsViewModel.refreshModels() // a delete removes it from the model dropdown too
                screen = target
            }) { ModelManagementScreen(manageViewModel) }
        }
        Screen.BENCHMARK -> {
            val benchmarkViewModel: BenchmarkViewModel =
                viewModel(factory = viewModelFactory { initializer { BenchmarkViewModel(graph) } })
            BackScaffold(title = "Benchmarks", current = Screen.BENCHMARK, onNavigate = { screen = it }) {
                BenchmarkScreen(benchmarkViewModel)
            }
        }
        Screen.MIX -> {
            val ttsState by ttsViewModel.state.collectAsState()
            // Not keyed to the main screen's selected model (issue #11) — Mix voices picks its own
            // model from the whole catalog, so it works even before anything is selected on MAIN.
            val mixViewModel: MixVoicesViewModel =
                viewModel(factory = viewModelFactory { initializer { MixVoicesViewModel(graph, ttsState.selected) } })
            BackScaffold(title = "Mix voices", current = Screen.MIX, onNavigate = { target ->
                ttsViewModel.refreshModels() // a saved mix may have become selectable
                screen = target
            }) { MixVoicesScreen(mixViewModel) }
        }
        Screen.LIBRARY -> {
            val ttsState by ttsViewModel.state.collectAsState()
            val libraryViewModel: ReadingLibraryViewModel =
                viewModel(factory = viewModelFactory { initializer { ReadingLibraryViewModel(graph, ttsState.text) } })
            BackScaffold(title = "Reading library", current = Screen.LIBRARY, onNavigate = { screen = it }) {
                ReadingLibraryScreen(
                    viewModel = libraryViewModel,
                    onOpen = { document, resume ->
                        // Loads the saved text into the SAME main-screen TtsViewModel the reader
                        // already uses; setText() itself looks up any saved DocumentMemory position
                        // for this content-derived id, so "resume" just reuses resumeFromSaved() —
                        // no second resume mechanism.
                        ttsViewModel.setText(document.text)
                        if (resume) ttsViewModel.resumeFromSaved()
                        screen = Screen.MAIN
                    },
                )
            }
        }
        Screen.COMPARE -> {
            val ttsState by ttsViewModel.state.collectAsState()
            val compareViewModel: CompareViewModel =
                viewModel(factory = viewModelFactory { initializer { CompareViewModel(graph, ttsState.text) } })
            BackScaffold(title = "Compare voices (A/B)", current = Screen.COMPARE, onNavigate = { target ->
                compareViewModel.stop()
                screen = target
            }) { CompareScreen(compareViewModel) }
        }
        Screen.HELP -> {
            val ttsState by ttsViewModel.state.collectAsState()
            BackScaffold(title = "Help", current = Screen.HELP, onNavigate = { screen = it }) {
                HelpScreen(
                    currentVersion = BuildConfig.VERSION_NAME,
                    update = ttsState.update,
                    checkStatus = ttsState.updateCheckStatus,
                    onCheckForUpdates = ttsViewModel::checkForUpdatesNow,
                    repoUrl = "https://github.com/${AppGraph.REPO_OWNER}/${AppGraph.REPO_NAME}",
                    currentTheme = theme,
                    onThemeSelected = onThemeSelected,
                )
            }
        }
    }
}

// Every sub-page's topBar: a hamburger drawer (issue #1) listing every destination, so no sub-page
// strands the user with only a back arrow. [current] is this screen's own [Screen] value so the
// drawer highlights it; [onNavigate] receives whichever destination the arrow (always MAIN) or the
// drawer picks, so each call site's own leaving-this-screen cleanup (refreshModels, stop, …) runs
// no matter which way the user leaves.
@Composable
private fun BackScaffold(
    title: String,
    current: Screen,
    onNavigate: (Screen) -> Unit,
    content: @Composable () -> Unit,
) {
    NavDrawerScaffold(
        title = title,
        current = current,
        onNavigate = onNavigate,
        onBack = { onNavigate(Screen.MAIN) },
        content = content,
    )
}
