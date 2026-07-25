package com.phonetts.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonetts.app.ui.nav.SettingsRow
import com.phonetts.core.audio.transform.BassCut
import com.phonetts.core.audio.transform.DeEsser
import com.phonetts.core.audio.transform.PresenceBoost
import com.phonetts.core.audio.transform.TempoStretch
import com.phonetts.core.prefs.AppTheme
import com.phonetts.core.prefs.ReadingTextPreferences
import com.phonetts.core.update.ApkDownloadState
import com.phonetts.core.update.UpdateStatus

/** Version/update-check state Settings needs, bundled so [SettingsScreen] stays under detekt's
 * function-parameter-count limit (mirrors [ManageFilterState]-style bundling elsewhere). */
data class SettingsUpdateInfo(
    val currentVersion: String,
    val update: UpdateStatus?,
    val updateDownload: ApkDownloadState,
    val checkStatus: String?,
    val repoUrl: String,
)

/** The update-check actions, bundled for the same reason as [SettingsUpdateInfo]. */
data class SettingsUpdateActions(
    val onCheckForUpdates: () -> Unit,
    val onDownloadUpdate: () -> Unit,
    val onCancelUpdateDownload: () -> Unit,
)

/**
 * Settings: a grouped list-of-rows screen, like Android's own Settings app - the "set once and
 * forget" knobs, PLUS the theme picker and "About & updates" (moved here from Help - a stateful
 * preference control doesn't belong on a help page), PLUS rows that navigate to Stats, Benchmarks,
 * and Help (one bottom-nav destination reached from one screen instead of three separate top-level
 * drawer rows). [onNavigateRow] is one callback for all three - adding a fourth row needs no new
 * parameter here, only a new [SettingsRow] entry.
 */
@Composable
fun SettingsScreen(
    viewModel: TtsViewModel,
    updateInfo: SettingsUpdateInfo,
    updateActions: SettingsUpdateActions,
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onNavigateRow: (SettingsRow) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection("Reading") {
            FontSizeSetting(state, viewModel)
        }

        SettingsSection("Audio cleanup") {
            SettingToggle(
                "Trim silence",
                "Remove leading and trailing silence from each clip.",
                state.trimSilence,
                viewModel::setTrimSilence,
            )
            SettingToggle(
                "Normalize volume",
                "Even out loudness so clips play at a consistent level.",
                state.normalizeVolume,
                viewModel::setNormalizeVolume,
            )
            SettingToggle(
                "Crossfade joins",
                "Blend sentence joins so they don't click or jump.",
                state.crossfadeJoins,
                viewModel::setCrossfadeJoins,
            )
        }

        SettingsSection("Tone") {
            SettingToggle(
                BassCut().displayName,
                "Cut low rumble for a clearer voice.",
                state.bassCut,
                viewModel::setBassCut,
            )
            SettingToggle(
                PresenceBoost().displayName,
                "Lift the presence range so speech sits forward.",
                state.presenceBoost,
                viewModel::setPresenceBoost,
            )
            SettingToggle(DeEsser().displayName, "Tame harsh \"s\" sounds.", state.deEss, viewModel::setDeEss)
        }

        SettingsSection("Playback tempo") {
            SettingToggle(
                "Playback speed (post-processed)",
                "A pitch-preserving speed-up applied on playback only - separate from the model's own Speed control.",
                state.tempoBoost,
                viewModel::setTempoBoost,
            )
            if (state.tempoBoost) {
                Text(
                    "Tempo ${"%.1f".format(state.tempoFactor)}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.tempoFactor,
                    onValueChange = viewModel::setTempoFactor,
                    valueRange = TempoStretch.MIN_FACTOR..TempoStretch.MAX_FACTOR,
                )
            }
        }

        SettingsSection("Long documents") {
            SettingToggle(
                "Spill audio to disk",
                "For book-length text: keep memory low by writing older audio to disk during a long synthesis.",
                state.longDocumentMode,
                viewModel::setLongDocumentMode,
            )
        }

        ThemeSection(currentTheme, onThemeSelected)
        UpdatesSection(updateInfo, updateActions)

        SettingsSection("More") {
            SettingsRow.entries.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                NavigationRow(row, onClick = { onNavigateRow(row) })
            }
        }
    }
}

/** A titled settings group card - mirrors Home's SectionCard so the two screens read as one app. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

/** A labeled toggle with a one-line description underneath, so each knob explains itself (issue #123).
 * The whole row - not just the [Switch] - is the tap target and the one accessibility node (issue
 * #141): [Modifier.toggleable] moves the click/[Role.Switch] semantics to the row itself (so its
 * touch target is the full row height, well past the 48dp minimum, not just the Switch's own bounds),
 * and [Modifier.semantics] with `mergeDescendants = true` merges the title + subtitle Text into that
 * one node so TalkBack announces "title, subtitle, switch, on/off" once instead of three separate
 * stops. The [Switch] itself gets `onCheckedChange = null` so it stops being its own second,
 * redundant click target. */
@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, onValueChange = onChecked, role = Role.Switch)
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // weight(1f) lets the title/subtitle wrap within their own share of the row rather than
        // pushing the fixed-size Switch off-screen (issues #20/#21).
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Reading font size (issue #29): scales the Home reading/editing text on TOP of the system font
 * size. Bounds come from [ReadingTextPreferences] (SSOT for them).
 */
@Composable
private fun FontSizeSetting(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    Text(
        "Text size scales the reading text; your system font size still applies on top.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The visible "A-"/"A+" glyphs read literally to TalkBack ("A dash", "A plus") with no other
        // clue what they do - an explicit contentDescription overrides that with the actual action
        // (issue #141) while leaving the compact glyph for sighted users.
        OutlinedButton(
            onClick = viewModel::decreaseTextScale,
            enabled = state.readingScale > ReadingTextPreferences.MIN_SCALE,
            modifier = Modifier.semantics { contentDescription = "Decrease text size" },
        ) { Text("A-") }
        OutlinedButton(
            onClick = viewModel::increaseTextScale,
            enabled = state.readingScale < ReadingTextPreferences.MAX_SCALE,
            modifier = Modifier.semantics { contentDescription = "Increase text size" },
        ) { Text("A+") }
        Text("${"%.0f".format(state.readingScale * PERCENT)}%", style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

// The reading-friendly theme picker (issue #12: a dropdown, not one radio row per theme). Options
// are derived from AppTheme.entries and labeled by AppTheme.displayName (SSOT: the enum is the
// single authority for which themes exist and what they're called). Moved here from Help - a
// stateful preference control does not belong on a help page.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSection(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsSection("Appearance") {
        Text(
            "Pick a color theme - sepia and true-black are tuned for long reading / OLED battery.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextField(
                value = currentTheme.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Theme") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppTheme.entries.forEach { theme ->
                    DropdownMenuItem(
                        text = { Text(theme.displayName) },
                        onClick = {
                            onThemeSelected(theme)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// Version + a manual update check. The app also checks automatically at launch (silent unless a
// newer build exists); this button lets the user re-check on demand and always gives feedback -
// "Up to date (v…)", a download offer, or a note when the check couldn't reach GitHub. Installing is
// always the user's choice (offer, never force). Moved here from Help for the same reason as
// [ThemeSection] - a stateful preference/action section, not how-to content.
@Composable
private fun UpdatesSection(
    info: SettingsUpdateInfo,
    actions: SettingsUpdateActions,
) {
    val uriHandler = LocalUriHandler.current
    SettingsSection("About & updates") {
        Text("Version ${info.currentVersion}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = actions.onCheckForUpdates) { Text("Check for updates") }
        info.update?.let { available ->
            UpdateActions(available, info.updateDownload, actions.onDownloadUpdate, actions.onCancelUpdateDownload)
        }
        OutlinedButton(onClick = { uriHandler.openUri(info.repoUrl) }) { Text("View on GitHub") }
        info.checkStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** One clickable row that navigates to a whole other screen (Stats/Benchmarks/Help) - a chevron
 * marks it as "goes somewhere" rather than a toggle, matching Android's own Settings app.
 * `mergeDescendants = true` (issue #141) announces the label + subtitle as one TalkBack stop
 * ("Stats, Usage totals, storage, and download diagnostics, button") instead of two separate ones;
 * the chevron stays a `null` contentDescription since it is purely decorative next to already-labeled
 * text. */
@Composable
private fun NavigationRow(
    row: SettingsRow,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label)
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PERCENT = 100f
