package com.phonetts.app.a11y

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.AppGraph
import com.phonetts.app.PhoneTtsApplication
import com.phonetts.app.benchmark.BenchmarkScreen
import com.phonetts.app.benchmark.BenchmarkViewModel
import com.phonetts.app.compare.CompareScreen
import com.phonetts.app.compare.CompareViewModel
import com.phonetts.app.library.ReadingLibraryScreen
import com.phonetts.app.library.ReadingLibraryViewModel
import com.phonetts.app.manage.ModelManagementScreen
import com.phonetts.app.manage.ModelManagementViewModel
import com.phonetts.app.stats.StatsScreen
import com.phonetts.app.stats.StatsViewModel
import com.phonetts.app.testing.ShadowEnvironmentAllFilesAccess
import com.phonetts.app.ui.HelpScreen
import com.phonetts.app.ui.OnboardingScreen
import com.phonetts.app.ui.SettingsScreen
import com.phonetts.app.ui.SettingsUpdateActions
import com.phonetts.app.ui.SettingsUpdateInfo
import com.phonetts.app.ui.TtsViewModel
import com.phonetts.app.ui.theme.PhoneTtsTheme
import com.phonetts.core.prefs.AppTheme
import com.phonetts.core.update.ApkDownloadState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #141: a repo-wide audit found only ~12 `contentDescription`s in the whole `:app` module,
 * concentrated in 4 files, with icon-only controls elsewhere (the Browse dismiss-error button) that
 * TalkBack would announce as a raw glyph or just "button". Hand-picking N assertions ("this
 * IconButton has a contentDescription") only guards the specific controls someone remembered to
 * write a test for - it says nothing about the next icon someone adds. This test instead walks the
 * REAL semantics tree Compose builds for every screen this session owns and fails on ANY clickable
 * node - `IconButton`, a `.clickable` row, a toggle, a FAB, anything with a click action - that has
 * no accessible name, so a newly added unlabeled icon fails CI the moment it is rendered by a test
 * here, with no per-control test needed.
 *
 * "Accessible name" mirrors what TalkBack actually reads for a node: its `ContentDescription`, its
 * `Text` (covers a plain `Button("Save")`, since `Modifier.clickable` merges descendants by default
 * so the button's own Text lands in ITS semantics config), its `EditableText` (a clickable text
 * field), or a `StateDescription`. A node with none of these is exactly the "TalkBack says just
 * `button`" bug issue #141 describes.
 *
 * IMPORTANT implementation note: every semantics read MUST happen while the [ComposeContentTestRule]
 * is still alive, i.e. inside the [Statement] passed to `rule.apply(...)` - a [SemanticsNode]'s
 * `.config` is computed from its still-attached [androidx.compose.ui.layout.LayoutNode], and the
 * rule's own teardown (run automatically once the wrapped `Statement.evaluate()` returns, as part of
 * the `TestRule` contract) detaches that tree. Fetching a [SemanticsNode] inside the rule but reading
 * its `.config` afterwards silently returns empty data (no crash, just every node looking
 * unlabeled) - so this walks the tree and produces plain `Boolean`/`String` verdicts BEFORE the rule
 * tears down, never leaking a live [SemanticsNode] across that boundary.
 *
 * Deliberately scoped to the screens this session owns (see CLAUDE.md's per-session file split) -
 * TtsScreen/HfBrowseScreen/MixVoicesScreen are mid-restructure by other concurrent work and are not
 * this test's job to gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowEnvironmentAllFilesAccess::class])
class AccessibilityAuditTest {

    /** One unlabeled clickable node's diagnostic info, captured while the node was still live. */
    private data class UnlabeledControl(val bounds: Rect, val role: String?)

    private fun graph(): AppGraph = ApplicationProvider.getApplicationContext<PhoneTtsApplication>().graph

    /** One entry per screen this session owns, each with a real ViewModel over empty/fake data -
     * mirrors [com.phonetts.app.ScreenSmokeTest]'s renderer map so this stays a faithful render, not
     * a synthetic one. */
    private fun renderers(): Map<String, @Composable () -> Unit> {
        val graph = graph()
        return mapOf(
            "SettingsScreen" to {
                SettingsScreen(
                    viewModel = TtsViewModel(graph),
                    updateInfo =
                        SettingsUpdateInfo(
                            currentVersion = "test",
                            update = null,
                            updateDownload = ApkDownloadState.Idle,
                            checkStatus = null,
                            repoUrl = "https://example.invalid/owner/repo",
                        ),
                    updateActions =
                        SettingsUpdateActions(
                            onCheckForUpdates = {},
                            onDownloadUpdate = {},
                            onCancelUpdateDownload = {},
                        ),
                    currentTheme = AppTheme.SYSTEM,
                    onThemeSelected = {},
                    onNavigateRow = {},
                )
            },
            "HelpScreen" to {
                HelpScreen(errorLogEntries = emptyList(), onClearErrorLog = {}, onReplayTutorial = {})
            },
            "OnboardingScreen" to {
                OnboardingScreen(onFinish = {})
            },
            "ModelManagementScreen" to {
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
            "BenchmarkScreen" to {
                BenchmarkScreen(BenchmarkViewModel(graph))
            },
            "StatsScreen" to {
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
            "ReadingLibraryScreen" to {
                ReadingLibraryScreen(
                    viewModel = ReadingLibraryViewModel(graph, initialText = ""),
                    onOpen = { _, _ -> },
                )
            },
            "CompareScreen" to {
                CompareScreen(CompareViewModel(graph, initialText = ""))
            },
        )
    }

    private fun SemanticsNode.accessibleName(): String? {
        config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it.isNotBlank() }?.let { return it }
        config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() }?.let { return it.text }
        config.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotBlank() }?.let { return it }
        config.getOrNull(SemanticsProperties.StateDescription)?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /** Mounts [content] on a fresh rule (Compose allows exactly one `setContent` per rule/activity -
     * see [com.phonetts.app.ScreenSmokeTest] for why this loop can't share one `@get:Rule`), then
     * returns the diagnostic info for every clickable node with no accessible name - computed INSIDE
     * the rule's lifecycle (see the class kdoc for why that matters). */
    private fun unlabeledClickablesOf(testName: String, content: @Composable () -> Unit): List<UnlabeledControl> {
        val rule = createComposeRule()
        var unlabeled: List<UnlabeledControl> = emptyList()
        val statement =
            object : Statement() {
                override fun evaluate() {
                    rule.setContent { PhoneTtsTheme { content() } }
                    rule.waitForIdle()
                    unlabeled =
                        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                            .filter { it.accessibleName() == null }
                            .map { UnlabeledControl(it.boundsInRoot, it.config.getOrNull(SemanticsProperties.Role)?.toString()) }
                }
            }
        rule.apply(statement, Description.createTestDescription(AccessibilityAuditTest::class.java, testName)).evaluate()
        return unlabeled
    }

    @Test
    fun everyClickableControlHasAnAccessibleName() {
        val failures = mutableListOf<String>()
        renderers().forEach { (screenName, content) ->
            val unlabeled =
                runCatching { unlabeledClickablesOf(screenName, content) }
                    .getOrElse { e -> throw AssertionError("$screenName failed to render: ${e.message}", e) }
            if (unlabeled.isNotEmpty()) {
                failures +=
                    "$screenName: ${unlabeled.size} clickable control(s) with no contentDescription/text/" +
                        "stateDescription (TalkBack would announce just \"button\") - " +
                        unlabeled.joinToString(", ") { "role=${it.role} bounds=${it.bounds}" }
            }
        }
        assertTrue(
            "Unlabeled clickable control(s) found - issue #141 (add a contentDescription describing " +
                "what the control DOES):\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    /** Targeted regression test for the "selected tab/mode must be announced" gap (issue #141 rule
     * 4): a plain [androidx.compose.material3.Button]/[androidx.compose.material3.OutlinedButton]
     * carries no selected-state semantics on its own the way a real `Tab`/`NavigationBarItem` does,
     * so [com.phonetts.app.compare.CompareScreen]'s A/B-vs-Tournament mode toggle has to add it
     * explicitly - this only exercises that one control's semantics, since the generic "has a name"
     * test above can't tell selected from unselected. Same live-node-only-inside-the-rule rule as
     * [unlabeledClickablesOf] applies here too. */
    @Test
    fun compareScreenModeToggleAnnouncesSelectedState() {
        val rule = createComposeRule()
        var selectedByLabel: Map<String, Boolean?> = emptyMap()
        val statement =
            object : Statement() {
                override fun evaluate() {
                    rule.setContent { PhoneTtsTheme { CompareScreen(CompareViewModel(graph(), initialText = "")) } }
                    rule.waitForIdle()
                    selectedByLabel =
                        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                            .mapNotNull { node ->
                                val name = node.accessibleName() ?: return@mapNotNull null
                                if (name != "A/B" && name != "Tournament") return@mapNotNull null
                                name to node.config.getOrNull(SemanticsProperties.Selected)
                            }
                            .toMap()
                }
            }
        rule.apply(
            statement,
            Description.createTestDescription(AccessibilityAuditTest::class.java, "compareScreenModeToggleAnnouncesSelectedState"),
        ).evaluate()

        assertTrue("Expected to find the A/B and Tournament mode buttons, found: $selectedByLabel", selectedByLabel.size == 2)
        assertTrue(
            "Each mode button should expose a `selected` semantics value so TalkBack announces which " +
                "mode is active, not just its label: $selectedByLabel",
            selectedByLabel.values.all { it != null },
        )
        assertTrue(
            "Exactly one of A/B or Tournament should be selected by default: $selectedByLabel",
            selectedByLabel.values.count { it == true } == 1,
        )
    }
}
