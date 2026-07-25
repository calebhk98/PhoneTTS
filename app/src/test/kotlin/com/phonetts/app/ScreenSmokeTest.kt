package com.phonetts.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.benchmark.BenchmarkScreen
import com.phonetts.app.benchmark.BenchmarkViewModel
import com.phonetts.app.compare.CompareScreen
import com.phonetts.app.compare.CompareViewModel
import com.phonetts.app.hf.HfBrowseScreen
import com.phonetts.app.hf.HfBrowseViewModel
import com.phonetts.app.library.ReadingLibraryScreen
import com.phonetts.app.library.ReadingLibraryViewModel
import com.phonetts.app.manage.ModelManagementScreen
import com.phonetts.app.manage.ModelManagementViewModel
import com.phonetts.app.stats.StatsScreen
import com.phonetts.app.stats.StatsViewModel
import com.phonetts.app.testing.FakeHttpClient
import com.phonetts.app.testing.ShadowEnvironmentAllFilesAccess
import com.phonetts.app.ui.HelpScreen
import com.phonetts.app.ui.MixVoicesScreen
import com.phonetts.app.ui.MixVoicesViewModel
import com.phonetts.app.ui.OnboardingScreen
import com.phonetts.app.ui.SettingsScreen
import com.phonetts.app.ui.SleepTimerHandle
import com.phonetts.app.ui.TtsScreen
import com.phonetts.app.ui.TtsViewModel
import com.phonetts.app.ui.theme.PhoneTtsTheme
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.prefs.AppTheme
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.resolver.InMemoryOverrideStore
import com.phonetts.core.resolver.Resolver
import com.phonetts.core.sideload.DirectoryBundleReader
import com.phonetts.core.sideload.ModelImporter
import com.phonetts.core.update.ApkDownloadState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-[Screen] smoke test: renders every screen's composable with a real (not mocked) ViewModel
 * wired to empty/fake data, and asserts composition completes without throwing. This is the test
 * the owner asked for directly - "what if I make a page that throws an error on load?".
 *
 * DRIVEN by [Screen.entries] via [everyRenderedScreenComposesWithoutThrowing]: a newly added
 * `Screen` value is exercised here automatically UNLESS it is also added to [renderers] (its own
 * composable needs a ViewModel this test cannot invent on its own) or to [excluded] (documented,
 * deliberate opt-out). Either omission fails [everyScreenIsRenderedOrExplicitlyExcluded] loudly,
 * so a screen can never silently go untested - see that test's assertion message.
 *
 * Each screen gets its OWN fresh [ComposeContentTestRule] (built by [createComposeRule], applied
 * manually via [ComposeContentTestRule.apply] rather than as a JUnit `@get:Rule` field) because
 * Compose's test harness allows exactly one `setContent` per rule instance/activity - reusing one
 * rule across screens throws "Cannot call setContent twice per test!" on the second screen, which
 * is exactly what a single shared `@get:Rule` produced here before this was written this way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowEnvironmentAllFilesAccess::class])
class ScreenSmokeTest {

    /** Screens this test cannot render headlessly, each with a documented reason. Adding to this
     * set is a deliberate, visible act - it is NOT how you silence a genuine rendering bug. */
    private val excluded: Map<Screen, String> = mapOf(
        Screen.ONBOARDING to
            "OnboardingScreen renders a HorizontalPager over several full-bleed illustration pages; " +
                "it has no ViewModel/state of its own (just onFinish: () -> Unit) so it is covered " +
                "by its own dedicated test (onboardingRendersWithoutThrowing) instead of the " +
                "ViewModel-driven renderer map.",
    )

    private fun graph(): AppGraph = ApplicationProvider.getApplicationContext<PhoneTtsApplication>().graph

    /** A throwaway offline [HfBrowseViewModel] for the Browse smoke test: a fake, empty HF search
     * response (no network in a unit test) and a fresh in-memory [ModelCatalog] so this stays fast
     * and deterministic - a "real-but-empty" ViewModel, not the network-backed one AppGraph builds. */
    private fun offlineHfBrowseViewModel(): HfBrowseViewModel {
        val catalog = ModelCatalog()
        val importer = ModelImporter(
            DirectoryBundleReader(),
            Resolver(engines = emptyList(), overrideStore = InMemoryOverrideStore()) { bundle -> bundle.id },
            catalog,
        )
        val downloader = com.phonetts.app.hf.HfDownloader(
            java.nio.file.Files.createTempDirectory("screen-smoke-test").toFile(),
            importer,
        )
        return HfBrowseViewModel(
            catalog = HfCatalog(FakeHttpClient("[]")),
            downloader = downloader,
            modelCatalog = catalog,
        )
    }

    /** One entry per non-excluded [Screen]: renders it with a real ViewModel over empty/fake data. */
    private fun renderers(): Map<Screen, @Composable () -> Unit> {
        val graph = graph()
        val ttsViewModel = TtsViewModel(graph)
        return mapOf(
            Screen.MAIN to {
                TtsScreen(
                    viewModel = ttsViewModel,
                    onNavigate = {},
                    appVersion = "test",
                    sleepTimer = SleepTimerHandle.None,
                )
            },
            Screen.BROWSE to {
                HfBrowseScreen(offlineHfBrowseViewModel())
            },
            Screen.MANAGE to {
                ModelManagementScreen(
                    ModelManagementViewModel(
                        modelManager = graph.modelManager,
                        resourceUsage = graph.resourceUsageStore,
                        availableRamBytes = { 0L },
                        totalRamBytes = { 0L },
                        benchmarkHistory = graph.benchmarkHistory,
                        deviceName = graph.deviceName,
                    ),
                )
            },
            Screen.BENCHMARK to {
                BenchmarkScreen(BenchmarkViewModel(graph))
            },
            Screen.STATS to {
                StatsScreen(
                    StatsViewModel(
                        usageStats = graph.usageStatsStore,
                        modelManager = graph.modelManager,
                        resourceUsageStore = graph.resourceUsageStore,
                        benchmarkHistory = graph.benchmarkHistory,
                        favoritesStore = graph.favoritesStore,
                        downloadDiagnosticsLog = graph.hfDownloader.diagnosticsLog,
                        deviceName = graph.deviceName,
                    ),
                )
            },
            Screen.MIX to {
                MixVoicesScreen(MixVoicesViewModel(graph, initialSelection = null))
            },
            Screen.LIBRARY to {
                ReadingLibraryScreen(
                    viewModel = ReadingLibraryViewModel(graph, initialText = ""),
                    onOpen = { _, _ -> },
                )
            },
            Screen.COMPARE to {
                CompareScreen(CompareViewModel(graph, initialText = ""))
            },
            Screen.STATS to {
                StatsScreen(
                    StatsViewModel(
                        usageStats = graph.usageStatsStore,
                        modelManager = graph.modelManager,
                        resourceUsageStore = graph.resourceUsageStore,
                        benchmarkHistory = graph.benchmarkHistory,
                        favoritesStore = graph.favoritesStore,
                        downloadDiagnosticsLog = graph.hfDownloader.diagnosticsLog,
                        deviceName = graph.deviceName,
                    ),
                )
            },
            Screen.SETTINGS to {
                SettingsScreen(ttsViewModel)
            },
            Screen.HELP to {
                HelpScreen(
                    currentVersion = "test",
                    update = null,
                    updateDownload = ApkDownloadState.Idle,
                    checkStatus = null,
                    onCheckForUpdates = {},
                    onDownloadUpdate = {},
                    onCancelUpdateDownload = {},
                    repoUrl = "https://example.invalid/owner/repo",
                    currentTheme = AppTheme.SYSTEM,
                    onThemeSelected = {},
                    errorLogEntries = emptyList(),
                )
            },
        )
    }

    @Test
    fun everyScreenIsRenderedOrExplicitlyExcluded() {
        val covered = renderers().keys + excluded.keys
        val missing = Screen.entries.filterNot { it in covered }
        assertTrue(
            "Screen(s) $missing are neither rendered by this smoke test nor in the documented " +
                "`excluded` set - add a renderer, or (only with a real reason) exclude it.",
            missing.isEmpty(),
        )
    }

    /**
     * Mounts [content] on a brand-new [ComposeContentTestRule] and runs it to completion, exactly
     * like a JUnit `@Rule` would but constructed by hand so each [Screen] in the loop below gets
     * an isolated rule/activity instead of sharing (and tripping) one across iterations.
     */
    private fun runOnFreshComposeRule(testName: String, content: @Composable () -> Unit) {
        val rule = createComposeRule()
        val statement = object : Statement() {
            override fun evaluate() {
                rule.setContent { PhoneTtsTheme { content() } }
                rule.waitForIdle()
            }
        }
        rule.apply(statement, Description.createTestDescription(ScreenSmokeTest::class.java, testName)).evaluate()
    }

    @Test
    fun everyRenderedScreenComposesWithoutThrowing() {
        val failures = mutableListOf<String>()
        renderers().forEach { (screen, render) ->
            runCatching { runOnFreshComposeRule(screen.name, render) }
                .onFailure { e -> failures += "$screen threw ${e::class.simpleName}: ${e.message}" }
        }
        assertTrue("One or more screens threw while composing:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun onboardingRendersWithoutThrowing() {
        // No ViewModel/state (just onFinish), so it doesn't fit the renderer map above - a plain,
        // dedicated smoke test instead.
        runCatching { runOnFreshComposeRule("ONBOARDING") { OnboardingScreen(onFinish = {}) } }
            .onFailure { e -> throw AssertionError("OnboardingScreen threw ${e::class.simpleName}: ${e.message}", e) }
    }
}
