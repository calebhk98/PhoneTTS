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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.phonetts.app.library.ReadingLibraryScreen
import com.phonetts.app.library.ReadingLibraryViewModel
import com.phonetts.app.stats.StatsScreen
import com.phonetts.app.stats.StatsViewModel
import com.phonetts.app.ui.HelpScreen
import com.phonetts.app.ui.OnboardingScreen
import com.phonetts.app.ui.SettingsScreen
import com.phonetts.app.ui.SettingsUpdateActions
import com.phonetts.app.ui.SettingsUpdateInfo
import com.phonetts.app.ui.SleepTimerHandle
import com.phonetts.app.ui.TtsScreen
import com.phonetts.app.ui.TtsViewModel
import com.phonetts.app.ui.nav.AppNavigationBar
import com.phonetts.app.ui.nav.SimpleScaffold
import com.phonetts.app.ui.nav.VoicesHubScreen
import com.phonetts.app.ui.nav.VoicesTab
import com.phonetts.app.ui.nav.voicesTabFor
import com.phonetts.app.ui.theme.PhoneTtsTheme
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.text.DocumentId
import com.phonetts.core.prefs.AppTheme

/**
 * Every screen in the app. The navigation model derived from this enum lives in
 * `com.phonetts.app.ui.nav` (see [com.phonetts.app.ui.nav.BottomDestination],
 * [com.phonetts.app.ui.nav.VoicesTab], [com.phonetts.app.ui.nav.SettingsRow]) rather than a single
 * hamburger drawer list: MAIN/LIBRARY/VOICES/SETTINGS are the 4 bottom-nav places; BROWSE/MANAGE/
 * MIX/COMPARE are tabs inside the Voices hub; STATS/BENCHMARK/HELP are rows under Settings;
 * ONBOARDING is a first-run-only flow, never a nav destination. [NavigationReachabilityTest] (app
 * test sources) guards every value here against becoming unreachable.
 *
 * Issue #135 fix: group the drawer into "Reading" and a collapsed "Advanced" section for QA/benchmark
 * tooling, so the main nav doesn't put these at equal weight with core reading features.
 */
enum class Screen {
    ONBOARDING,
    MAIN,
    BROWSE,
    MANAGE,
    BENCHMARK,
    HELP,
    MIX,
    LIBRARY,
    COMPARE,
    SETTINGS,
    STATS,
    VOICES,

    // New: groupings for the drawer UI
    READING_GROUP,
    ADVANCED_GROUP,
}

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as PhoneTtsApplication).graph }
    private val ttsViewModel: TtsViewModel by viewModels {
        viewModelFactory { initializer { TtsViewModel(graph) } }
    }
    // A Compose State (not a plain var) so AppNav recomposes - and rebuilds the SleepTimerHandle
    // it hands TtsScreen - the moment the service connects/disconnects.
    private val binderState = mutableStateOf<PlaybackService.LocalBinder?>(null)

    // POST_NOTIFICATIONS is declared in the manifest, but Android 13+ (API 33) also requires a
    // RUNTIME grant - without it the system silently drops EVERY notification, so the download,
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
        bindPlaybackService()
        keepServiceStartedWhilePlaying()
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

    // Bind for the whole lifetime of the Activity, not just while it is in the foreground.
    //
    // This used to bind in onStart and unbind + detachController in onStop, which meant the service
    // lost its controller the instant the app backgrounded - i.e. exactly when the screen locks and
    // lock-screen controls are the only controls there are. Every MediaSession callback then hit
    // `controller?.pause() ?: Unit` and silently did nothing. Worse, a service that is only ever
    // BOUND (never started) is eligible for destruction as soon as its last client unbinds, so the
    // notification and media session could disappear outright mid-playback.
    //
    // The service is also STARTED (not merely bound) when playback begins - see bindPlaybackService's
    // caller in onCreate - so it survives independently of this Activity.
    private fun bindPlaybackService() {
        bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
    }

    /**
     * START the playback service the moment playback begins, so it is a genuinely started foreground
     * service rather than a bind-only one.
     *
     * A bound-only service dies with its last client. Because the Activity was the only client and it
     * unbound whenever it backgrounded, the service could be destroyed mid-playback - taking the
     * media session, the notification and its controls with it, and leaving the app process with no
     * foreground component to protect it from being reclaimed on a 4 GB device. Starting it means its
     * lifetime is owned by playback, not by whether a screen happens to be visible.
     */
    private fun keepServiceStartedWhilePlaying() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                ttsViewModel.state.collect { state ->
                    if (!state.playing) return@collect
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, PlaybackService::class.java),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsViewModel.onUserStopRequested = null
        binderState.value?.detachController()
        runCatching { unbindService(connection) } // no-op if the bind never succeeded
        binderState.value = null
    }

    private fun forwardState(localBinder: PlaybackService.LocalBinder) {
        lifecycleScope.launch {
            // CREATED, not STARTED, and for the same reason keepServiceStartedWhilePlaying uses
            // CREATED: a STARTED-scoped collector stops the instant the Activity backgrounds, which
            // is exactly when the lock screen matters. This is the ONLY path that pushes
            // playing/paused into the service, so while backgrounded the notification could not
            // update and - worse - onStateChanged(playing=false) never arrived, so finishPlayback()
            // never ran: a lock-screen Stop really stopped the audio but left an ongoing "Playing"
            // notification up until the app was reopened. I widened the sibling collector to CREATED
            // earlier and missed this one; they have to agree.
            repeatOnLifecycle(Lifecycle.State.CREATED) {
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

/**
 * The app's whole navigation state: which [Screen] is current, and (only meaningful while on
 * [Screen.VOICES]) which [VoicesTab] the hub is showing. Onboarding is a special case rendered
 * full-bleed with no bottom nav at all, exactly as before; everything else sits inside one
 * [Scaffold] whose `bottomBar` is the always-present [AppNavigationBar] (native IA: a bottom nav
 * bar for 3-5 places, never a hamburger drawer - see `com.phonetts.app.ui.nav.BottomDestination`).
 */
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
    var voicesTab by remember { mutableStateOf(VoicesTab.BROWSE) }
    val binder by binderState

    // Load shared/handed-in text into the reader once, when the activity was opened via a share.
    LaunchedEffect(sharedText) {
        sharedText?.let(ttsViewModel::setText)
    }

    if (screen == Screen.ONBOARDING) {
        OnboardingScreen(
            onFinish = {
                graph.onboardingState.markSeen()
                screen = Screen.MAIN
            },
        )
        return
    }

    // Every navigation request goes through here. A request for one of the Voices hub's own
    // screens (Browse/Manage/Mix/Compare) opens the hub on that tab instead of treating it as a
    // standalone destination - "which voice do I use" is one place, not four.
    val navigateTo: (Screen) -> Unit = { target ->
        val tab = voicesTabFor(target)
        if (tab != null) {
            voicesTab = tab
            screen = Screen.VOICES
        } else {
            screen = target
        }
    }

    Scaffold(
        bottomBar = { AppNavigationBar(current = screen, onSelect = navigateTo) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AppNavContent(
                screen = screen,
                voicesTab = voicesTab,
                graph = graph,
                ttsViewModel = ttsViewModel,
                binder = binder,
                theme = theme,
                onThemeSelected = onThemeSelected,
                onNavigate = navigateTo,
                onSelectVoicesTab = { voicesTab = it },
                onBackToSettings = { screen = Screen.SETTINGS },
            )
        }
    }
}

/** The actual per-[Screen] content, split out of [AppNav] purely to keep that function short. */
@Composable
private fun AppNavContent(
    screen: Screen,
    voicesTab: VoicesTab,
    graph: AppGraph,
    ttsViewModel: TtsViewModel,
    binder: PlaybackService.LocalBinder?,
    theme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onNavigate: (Screen) -> Unit,
    onSelectVoicesTab: (VoicesTab) -> Unit,
    onBackToSettings: () -> Unit,
) {
    when (screen) {
        Screen.MAIN ->
            TtsScreen(
                viewModel = ttsViewModel,
                onNavigate = onNavigate,
                appVersion = BuildConfig.VERSION_NAME,
                sleepTimer = remember(binder) { binder.toSleepTimerHandle() },
            )
        Screen.LIBRARY -> LibraryDestination(graph, ttsViewModel, onNavigateHome = { onNavigate(Screen.MAIN) })
        Screen.VOICES ->
            VoicesHubScreen(
                graph = graph,
                ttsViewModel = ttsViewModel,
                selectedTab = voicesTab,
                onSelectTab = onSelectVoicesTab,
            )
        Screen.SETTINGS -> SettingsDestination(graph, ttsViewModel, theme, onThemeSelected, onNavigate)
        Screen.STATS -> StatsDestination(graph, onBackToSettings)
        Screen.BENCHMARK -> BenchmarkDestination(graph, onBackToSettings)
        Screen.HELP ->
            HelpDestination(
                graph = graph,
                onBack = onBackToSettings,
                onReplayTutorial = { onNavigate(Screen.ONBOARDING) },
            )
        // Reachable only through the Voices hub: [onNavigate] above always redirects a request for
        // one of these four to Screen.VOICES plus the matching tab, so `screen` itself is never
        // actually one of them - kept as an explicit branch so this `when` stays exhaustive against
        // Screen.entries (a new Screen value that isn't handled anywhere is a compile error).
        Screen.BROWSE, Screen.MANAGE, Screen.MIX, Screen.COMPARE -> Unit
        // Handled above, before this composable is ever reached.
        Screen.ONBOARDING -> Unit
    }
}

@Composable
private fun LibraryDestination(
    graph: AppGraph,
    ttsViewModel: TtsViewModel,
    onNavigateHome: () -> Unit,
) {
    val ttsState by ttsViewModel.state.collectAsState()
    val libraryViewModel: ReadingLibraryViewModel =
        viewModel(factory = viewModelFactory { initializer { ReadingLibraryViewModel(graph, ttsState.text) } })
    SimpleScaffold(title = "Library") {
        ReadingLibraryScreen(
            viewModel = libraryViewModel,
            onOpen = { document, resume ->
                // Loads the saved text into the SAME main-screen TtsViewModel the reader already
                // uses; setText() itself looks up any saved DocumentMemory position for this
                // content-derived id, so "resume" just reuses resumeFromSaved() - no second resume
                // mechanism.
                ttsViewModel.setText(document.text)
                restorePerDocumentVoice(graph, ttsViewModel, document.text)
                if (resume) ttsViewModel.resumeFromSaved()
                onNavigateHome()
            },
            onJumpToBookmark = { document, bookmark ->
                ttsViewModel.setText(document.text)
                restorePerDocumentVoice(graph, ttsViewModel, document.text)
                ttsViewModel.seekToSentence(bookmark.sentenceIndex)
                onNavigateHome()
            },
        )
    }
}

/**
 * Issue #147: restore the voice/model/speed this particular document was last read with, rather
 * than whatever was used globally last. Someone reading a novel in one voice and technical docs in
 * another should not have to re-pick on every switch.
 *
 * [DocumentMemory.preferredSelection] fails closed for us: a document with no recorded model, or
 * one whose model has since been deleted, returns null and we simply leave the global last-used
 * selection alone - never stranding the user on a model that is no longer installed.
 */
private fun restorePerDocumentVoice(
    graph: AppGraph,
    ttsViewModel: TtsViewModel,
    text: String,
) {
    val preferred =
        graph.documentMemory.preferredSelection(DocumentId.of(text), ttsViewModel.state.value.models) ?: return
    ttsViewModel.selectFavoriteVoice(preferred.descriptor.modelId, preferred.voiceId)
    ttsViewModel.setParam(ModelParameter.SPEED_ID, preferred.speed)
}

@Composable
private fun SettingsDestination(
    graph: AppGraph,
    ttsViewModel: TtsViewModel,
    theme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val ttsState by ttsViewModel.state.collectAsState()
    SimpleScaffold(title = "Settings") {
        SettingsScreen(
            viewModel = ttsViewModel,
            updateInfo =
                SettingsUpdateInfo(
                    currentVersion = BuildConfig.VERSION_NAME,
                    update = ttsState.update,
                    updateDownload = ttsState.updateDownload,
                    checkStatus = ttsState.updateCheckStatus,
                    repoUrl = "https://github.com/${AppGraph.REPO_OWNER}/${AppGraph.REPO_NAME}",
                ),
            updateActions =
                SettingsUpdateActions(
                    onCheckForUpdates = ttsViewModel::checkForUpdatesNow,
                    onDownloadUpdate = ttsViewModel::downloadUpdate,
                    onCancelUpdateDownload = ttsViewModel::cancelUpdateDownload,
                ),
            currentTheme = theme,
            onThemeSelected = onThemeSelected,
            onNavigateRow = { row -> onNavigate(row.screen) },
        )
    }
}

@Composable
private fun StatsDestination(graph: AppGraph, onBack: () -> Unit) {
    val statsViewModel: StatsViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        StatsViewModel(
                            usageStats = graph.usageStatsStore,
                            modelManager = graph.modelManager,
                            resourceUsageStore = graph.resourceUsageStore,
                            benchmarkHistory = graph.benchmarkHistory,
                            favoritesStore = graph.favoritesStore,
                            downloadDiagnosticsLog = graph.hfDownloader.diagnosticsLog,
                            deviceName = graph.deviceName,
                        )
                    }
                },
        )
    SimpleScaffold(title = "Stats", onBack = onBack) { StatsScreen(statsViewModel) }
}

@Composable
private fun BenchmarkDestination(graph: AppGraph, onBack: () -> Unit) {
    val benchmarkViewModel: BenchmarkViewModel =
        viewModel(factory = viewModelFactory { initializer { BenchmarkViewModel(graph) } })
    SimpleScaffold(title = "Benchmarks", onBack = onBack) { BenchmarkScreen(benchmarkViewModel) }
}

@Composable
private fun HelpDestination(
    graph: AppGraph,
    onBack: () -> Unit,
    onReplayTutorial: () -> Unit,
) {
    SimpleScaffold(title = "Help", onBack = onBack) {
        HelpScreen(
            // The durable error log's rows, newest-first, one formatted line each so the user has a
            // copyable "why" a model (e.g. a LiteRT .tflite) failed to load or run.
            errorLogEntries = graph.durableErrorLog.entries().map { "[${it.source}] ${it.message}" },
            // Crash rows are written by CrashReporter's uncaught-exception handler and read back
            // here, so Help's copy/clear actually has something to show after a crash.
            onClearErrorLog = { graph.durableErrorLog.clear() },
            onReplayTutorial = onReplayTutorial,
        )
    }
}
