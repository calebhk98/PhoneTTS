package com.phonetts.app.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.phonetts.app.Screen

/**
 * The single bottom [NavigationBar] for the app's 4 top-level places - replaces the
 * hamburger-drawer-for-everything nav (a drawer is the worse option on a phone-width screen; see
 * [BottomDestination]'s doc for the Material 3 guidance this follows). Always visible, including
 * on a Settings row's own detail screen (Stats/Benchmarks/Help), so there is always one tap to any
 * other place without backing out first - and, just as importantly, [NavigationBar] is what
 * actually consumes the navigation-bar system inset on every screen, since it applies that inset
 * itself by default (required for edge-to-edge, targetSdk 35).
 */
@Composable
fun AppNavigationBar(current: Screen, onSelect: (Screen) -> Unit) {
    val activeDestination = current.parentBottomDestination()
    NavigationBar {
        BottomDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == activeDestination,
                onClick = { onSelect(destination.screen) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}

/**
 * Which [BottomDestination] a screen "lives under", for highlighting the bar. Stats/Benchmarks/Help
 * are reached from Settings' own rows, so all three (and Settings itself) highlight Settings;
 * Browse/Manage/Mix/Compare are reached from the Voices hub's tabs, so all four (and Voices itself)
 * highlight Voices. Onboarding never reaches this call - it is shown full-bleed before the bar
 * exists at all (see MainActivity's `AppNav`).
 *
 * An exhaustive `when` over [Screen] on purpose: a newly added `Screen` value that isn't handled
 * here is a compile error, not a silently-unreachable destination.
 */
fun Screen.parentBottomDestination(): BottomDestination =
    when (this) {
        Screen.MAIN, Screen.ONBOARDING -> BottomDestination.HOME
        Screen.LIBRARY -> BottomDestination.LIBRARY
        Screen.VOICES, Screen.BROWSE, Screen.MANAGE, Screen.MIX, Screen.COMPARE -> BottomDestination.VOICES
        Screen.SETTINGS, Screen.STATS, Screen.BENCHMARK, Screen.HELP -> BottomDestination.SETTINGS
    }

/**
 * A plain top app bar with a title and, optionally, a back arrow - used for the top-level tab
 * screens (no [onBack]: Library, Voices, Settings) and for a Settings row's own detail screen
 * (with [onBack] returning to Settings: Stats, Benchmarks, Help). No hamburger, no drawer: the
 * always-present [AppNavigationBar] is the one way to jump anywhere else instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { onBack?.let { BackIcon(it) } },
            )
        },
    ) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }
    }
}

@Composable
private fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}
