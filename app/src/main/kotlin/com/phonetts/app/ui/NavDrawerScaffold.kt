package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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

// The destination list every drawer (main screen and every sub-page) offers, paired with its
// label. This is the SSOT for "what can I jump to" - TtsScreen's own drawer and this one both draw
// from the same [Screen] enum, so registering a new destination is a one-line addition here rather
// than a per-screen special case (a "Sideload folder" launcher isn't a [Screen], so it stays out of
// this list and lives only in TtsScreen, which owns that launcher).
private val NAV_DESTINATIONS: List<Pair<Screen, String>> =
    listOf(
        Screen.MAIN to "Home",
        Screen.BROWSE to "Browse models",
        Screen.MANAGE to "Manage models",
        Screen.MIX to "Mix voices",
        Screen.LIBRARY to "Reading library",
        Screen.COMPARE to "Compare voices (A/B)",
        Screen.BENCHMARK to "Benchmarks",
        Screen.HELP to "Help",
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
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val navigate: (Screen) -> Unit = { destination ->
        scope.launch { drawerState.close() }
        onNavigate(destination)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { NavDrawerContent(current = current, onNavigate = navigate) },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            // Matches TtsScreen's own hamburger glyph (no material-icons dependency).
                            Text("☰", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                )
            },
        ) { innerPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }
        }
    }
}

@Composable
private fun NavDrawerContent(
    current: Screen,
    onNavigate: (Screen) -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PhoneTTS", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NAV_DESTINATIONS.forEach { (destination, label) ->
                NavigationDrawerItem(
                    label = { Text(label) },
                    selected = destination == current,
                    onClick = { onNavigate(destination) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
    }
}
