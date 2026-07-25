package com.phonetts.app.ui.nav

import com.phonetts.app.Screen

/**
 * The Voices hub's own `SecondaryTabRow` entries (Browse | Downloaded | Mix | Compare). All four
 * answer the same question - "which voice do I use?" - so they live together under one bottom-nav
 * destination instead of four separate top-level drawer rows.
 *
 * [Screen.BROWSE]/[Screen.MANAGE]/[Screen.MIX]/[Screen.COMPARE] keep their original [Screen] values
 * (existing call sites - [com.phonetts.app.ui.TtsScreen]'s empty-state "Browse" action, the seam
 * tests - already navigate to them by name), they are just no longer reached as standalone
 * destinations: [voicesTabFor] resolves any of them to the hub tab that now hosts their content.
 */
enum class VoicesTab(val label: String, val screen: Screen) {
    BROWSE("Browse", Screen.BROWSE),
    DOWNLOADED("Downloaded", Screen.MANAGE),
    MIX("Mix", Screen.MIX),
    COMPARE("Compare", Screen.COMPARE),
}

/** The [VoicesTab] that hosts [screen], or null if [screen] isn't one of the hub's four tabs. */
fun voicesTabFor(screen: Screen): VoicesTab? = VoicesTab.entries.find { it.screen == screen }
