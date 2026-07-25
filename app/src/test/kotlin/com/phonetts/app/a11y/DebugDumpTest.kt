package com.phonetts.app.a11y

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.AppGraph
import com.phonetts.app.PhoneTtsApplication
import com.phonetts.app.testing.ShadowEnvironmentAllFilesAccess
import com.phonetts.app.ui.SettingsScreen
import com.phonetts.app.ui.SettingsUpdateActions
import com.phonetts.app.ui.SettingsUpdateInfo
import com.phonetts.app.ui.TtsViewModel
import com.phonetts.app.ui.theme.PhoneTtsTheme
import com.phonetts.core.prefs.AppTheme
import com.phonetts.core.update.ApkDownloadState
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowEnvironmentAllFilesAccess::class])
class DebugDumpTest {
    private fun graph(): AppGraph = ApplicationProvider.getApplicationContext<PhoneTtsApplication>().graph

    private fun dump(node: SemanticsNode, indent: String, sb: StringBuilder) {
        val hasClick = node.config.getOrNull(SemanticsActions.OnClick) != null
        val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)
        val text = node.config.getOrNull(SemanticsProperties.Text)
        sb.append("$indent id=${node.id} click=$hasClick cd=$cd text=$text\n")
        node.children.forEach { dump(it, "$indent  ", sb) }
    }

    @Test
    fun dumpSettings() {
        val rule = createComposeRule()
        val sb = StringBuilder()
        val statement =
            object : Statement() {
                override fun evaluate() {
                    rule.setContent {
                        PhoneTtsTheme {
                            SettingsScreen(
                                viewModel = TtsViewModel(graph()),
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
                        }
                    }
                    rule.waitForIdle()
                    dump(rule.onRoot().fetchSemanticsNode(), "", sb)
                }
            }
        rule.apply(statement, Description.createTestDescription(DebugDumpTest::class.java, "dumpSettings")).evaluate()
        throw AssertionError(sb.toString())
    }
}
