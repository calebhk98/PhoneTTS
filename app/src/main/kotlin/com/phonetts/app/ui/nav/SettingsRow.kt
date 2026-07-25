package com.phonetts.app.ui.nav

import com.phonetts.app.Screen

/**
 * Rows on the Settings screen that navigate to a whole other screen rather than toggle a
 * preference in place - Stats, Benchmarks, and Help. Modeled as an enum the same way
 * [VoicesTab]/[BottomDestination] are so [com.phonetts.app.NavigationReachabilityTest] can derive
 * reachability from the real navigation model instead of a hand-tracked list.
 */
enum class SettingsRow(val label: String, val subtitle: String, val screen: Screen) {
    STATS("Stats", "Usage totals, storage, and download diagnostics.", Screen.STATS),
    BENCHMARKS("Benchmarks", "Measure real-time factor per model on this device.", Screen.BENCHMARK),
    HELP("Help", "How-to, troubleshooting, and the error log.", Screen.HELP),
}
