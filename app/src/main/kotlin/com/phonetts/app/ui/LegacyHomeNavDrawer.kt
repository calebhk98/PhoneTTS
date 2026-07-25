package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonetts.app.Screen

/**
 * LEGACY. The app's real navigation is now the bottom `AppNavigationBar` + the Voices hub's own
 * tabs + Settings' own rows (see `com.phonetts.app.ui.nav`, and MainActivity's `AppNav`) - a drawer
 * is the worse option on a phone-width screen per Material 3's own guidance (a drawer is explicitly
 * discouraged on compact widths; Material 3 Expressive drops drawers entirely), and "Import model
 * from folder" living as a drawer row was exactly the "one-shot action posing as a destination"
 * mistake that redesign fixes - it is now an `ExtendedFloatingActionButton` on Downloaded models.
 *
 * This file survives ONLY because [TtsScreen] (Home) still renders its own internal
 * `ModalNavigationDrawer` around [NavDrawerContent] directly, and TtsScreen.kt was out of scope to
 * edit in this pass (owned by a different work item). Nothing else in the app calls into this file
 * anymore - a follow-up on TtsScreen.kt should delete Home's own leftover hamburger/drawer (it now
 * only duplicates what the bottom nav already offers) and this file along with it.
 *
 * [NAV_ITEMS] is deliberately short now - no more "Import model from folder" entry, and
 * Browse/Manage/Mix/Compare are folded into the single "Voices" entry that hosts them - since
 * everything it lists is also one tap away from the bottom nav already.
 */
sealed interface NavItem {
    val label: String

    data class ToScreen(val screen: Screen, override val label: String) : NavItem

    /** Kept only so [NavAction] stays a real, matchable type for [TtsScreen]'s existing
     * `when (action) { NavAction.IMPORT_MODEL_FOLDER -> ... }` handler. No [NavItem.RunAction] entry
     * exists in [NAV_ITEMS] anymore (the mission-critical bit: Import is off the nav entirely), so
     * that handler can no longer actually be reached from here - it is dead code kept alive only
     * for source compatibility with the file this doc explains. */
    data class RunAction(val action: NavAction, override val label: String) : NavItem
}

/** See [NavItem.RunAction]'s doc - retained only for [TtsScreen]'s still-compiling `when`. */
enum class NavAction { IMPORT_MODEL_FOLDER }

/** See the file doc - Home's (TtsScreen.kt's) own leftover drawer list. */
val NAV_ITEMS: List<NavItem> =
    listOf(
        NavItem.ToScreen(Screen.MAIN, "Home"),
        NavItem.ToScreen(Screen.LIBRARY, "Library"),
        NavItem.ToScreen(Screen.VOICES, "Voices"),
        NavItem.ToScreen(Screen.SETTINGS, "Settings"),
        NavItem.ToScreen(Screen.STATS, "Stats"),
        NavItem.ToScreen(Screen.BENCHMARK, "Benchmarks"),
        NavItem.ToScreen(Screen.HELP, "Help"),
    )

/**
 * Renders [NAV_ITEMS]. Public only because [TtsScreen] still renders this content inside its own
 * `ModalNavigationDrawer` (see the file doc) - nothing else in the app uses it.
 */
@Composable
fun NavDrawerContent(
    current: Screen,
    appVersion: String?,
    onNavigate: (Screen) -> Unit,
    onAction: (NavAction) -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PhoneTTS", style = MaterialTheme.typography.headlineSmall)
            Text(
                appVersion?.let { "Offline text-to-speech · v$it" } ?: "Offline text-to-speech",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NAV_ITEMS.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label) },
                    selected = item is NavItem.ToScreen && item.screen == current,
                    onClick = {
                        when (item) {
                            is NavItem.ToScreen -> onNavigate(item.screen)
                            is NavItem.RunAction -> onAction(item.action)
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
    }
}
