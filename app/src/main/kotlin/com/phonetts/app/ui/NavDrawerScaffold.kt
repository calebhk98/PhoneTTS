package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonetts.app.Screen
import kotlinx.coroutines.launch

/**
 * One entry in the navigation drawer. Most entries go to a [Screen]; a few run an action that isn't
 * a screen at all (importing a model folder opens the system file picker and comes straight back).
 * Modelling both as [NavItem] is what lets ONE list describe the whole drawer - the previous design
 * could only express screens, which is precisely why the odd one out ("Sideload folder") ended up
 * hand-written into a second, divergent drawer.
 */
sealed interface NavItem {
    val label: String

    data class ToScreen(val screen: Screen, override val label: String) : NavItem

    data class RunAction(val action: NavAction, override val label: String) : NavItem
}

/** Drawer entries that do something in place instead of navigating. */
enum class NavAction { IMPORT_MODEL_FOLDER }

/**
 * THE navigation drawer, in one list. Every surface that shows a drawer renders exactly this - there
 * is no second list anywhere, so the menu cannot differ depending on which screen you opened it from.
 *
 * It used to: the Home screen had its own hand-written drawer with its own hardcoded sequence of
 * links (10 entries, its own selected-state rule) while every other screen rendered a 9-entry list
 * from here. They had already drifted, which is exactly what "the sidebar changes based on what menu
 * you are on" describes. Adding, removing, or reordering a destination is now a single edit here.
 */
val NAV_ITEMS: List<NavItem> =
    listOf(
        NavItem.ToScreen(Screen.MAIN, "Home"),
        NavItem.ToScreen(Screen.BROWSE, "Browse models"),
        NavItem.ToScreen(Screen.MANAGE, "Manage models"),
        NavItem.RunAction(NavAction.IMPORT_MODEL_FOLDER, "Import model from folder"),
        NavItem.ToScreen(Screen.MIX, "Mix voices"),
        NavItem.ToScreen(Screen.LIBRARY, "Reading library"),
        NavItem.ToScreen(Screen.COMPARE, "Compare voices (A/B)"),
        NavItem.ToScreen(Screen.BENCHMARK, "Benchmarks"),
        NavItem.ToScreen(Screen.SETTINGS, "Settings"),
        NavItem.ToScreen(Screen.HELP, "Help"),
    )

/**
 * The hamburger-drawer scaffold every sub-page (Browse, Manage, Benchmark, Help, Mix, Library,
 * Compare) uses, matching the main screen's layout: the hamburger lives on the left and there is
 * no back arrow (issue #1) - the same [Screen] destinations TtsScreen's own drawer lists are one
 * tap away from anywhere in the app. [current] highlights where you are; [onNavigate] drives the
 * hamburger's destinations. [onBack] is currently unused by this scaffold but stays part of the
 * signature since other screens still pass it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavDrawerScaffold(
    title: String,
    current: Screen,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
    onAction: (NavAction) -> Unit,
    appVersion: String? = null,
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val navigate: (Screen) -> Unit = { destination ->
        scope.launch { drawerState.close() }
        onNavigate(destination)
    }
    val act: (NavAction) -> Unit = { action ->
        scope.launch { drawerState.close() }
        onAction(action)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            NavDrawerContent(current = current, appVersion = appVersion, onNavigate = navigate, onAction = act)
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open navigation menu")
                        }
                    },
                )
            },
        ) { innerPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }
        }
    }
}

/**
 * Renders [NAV_ITEMS]. Public because the Home screen drives its own [ModalNavigationDrawer] (it
 * needs a drawer around a body this scaffold doesn't own) - it renders THIS content rather than
 * keeping a private copy, which is the whole point.
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
                    // An action entry is never "where you are", so only screen entries can be selected.
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
