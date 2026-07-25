package com.phonetts.app

import com.phonetts.app.ui.nav.BottomDestination
import com.phonetts.app.ui.nav.SettingsRow
import com.phonetts.app.ui.nav.VoicesTab
import com.phonetts.app.ui.nav.parentBottomDestination
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bottom-nav/hub-tabs/settings-rows navigation model against the exact bug class the
 * owner asked for: a new [Screen] value added to the enum but never wired into anything that can
 * reach it.
 *
 * This is DERIVED from the code, not a hand-copied list: it walks [Screen.entries] against
 * [BottomDestination] (the 4 bottom-nav places), [VoicesTab] (the Voices hub's own tabs), and
 * [SettingsRow] (Settings' own navigable rows) - the three lists that replaced the single
 * hamburger-drawer-for-everything list (a drawer is the worse option on a phone/compact width per
 * Material 3's own guidance, and "Import model from folder" living as a drawer row was exactly the
 * "one-shot action posing as a destination" mistake that redesign fixes). Adding a destination to
 * any of the three enums automatically comes under test here with no edit to this file. The only
 * thing that ever needs a deliberate edit is [nonNavEntryPoints] below, and only for a screen that
 * is genuinely reached some other way than one of those three lists.
 */
class NavigationReachabilityTest {

    /**
     * [Screen] values that are legitimately reachable WITHOUT appearing in [BottomDestination],
     * [VoicesTab], or [SettingsRow] - each one documented with exactly how a user gets there. Adding
     * to this list is a deliberate, visible act: anything not in one of the three real nav lists and
     * not explicitly allow-listed here fails this test.
     */
    private val nonNavEntryPoints: Map<Screen, String> = mapOf(
        Screen.ONBOARDING to
            "First-launch-only walkthrough (MainActivity.AppNav), plus Help's \"Replay tutorial\" row: " +
                "shown full-bleed before the bottom nav bar exists at all, never itself a bottom-nav/hub-tab/" +
                "settings-row destination.",
    )

    private fun bottomNavScreens(): Set<Screen> = BottomDestination.entries.map { it.screen }.toSet()

    private fun voicesTabScreens(): Set<Screen> = VoicesTab.entries.map { it.screen }.toSet()

    private fun settingsRowScreens(): Set<Screen> = SettingsRow.entries.map { it.screen }.toSet()

    private fun allNavigableScreens(): Set<Screen> =
        bottomNavScreens() + voicesTabScreens() + settingsRowScreens() + nonNavEntryPoints.keys

    @Test
    fun `every Screen is reachable from the bottom nav, a Voices tab, a Settings row, or a documented entry point`() {
        val unreachable = Screen.entries.filterNot { it in allNavigableScreens() }
        assertTrue(
            "Screen(s) $unreachable are in none of BottomDestination/VoicesTab/SettingsRow and are not " +
                "in the documented nonNavEntryPoints allow-list in this test - a user (and this test) has " +
                "no way to reach them. Either add a nav-list entry or, if it's a genuine non-nav entry " +
                "point (like ONBOARDING), add it to nonNavEntryPoints with a comment explaining how.",
            unreachable.isEmpty(),
        )
    }

    @Test
    fun `documented entry points still name real Screen values`() {
        // Catches the mirror-image mistake: a Screen removed from the enum but left dangling in the
        // allow-list above (the map key type already prevents typos; this just keeps the map honest
        // if Screen ever loses a value some other way).
        val entries = Screen.entries.toSet()
        assertTrue(nonNavEntryPoints.keys.all { it in entries })
    }

    @Test
    fun `bottom nav has the 3-5 top-level places Material 3 recommends for a phone`() {
        // m3.material.io/components/navigation-bar/guidelines: a NavigationBar is meant for 3-5
        // top-level destinations on a compact width; this app uses exactly 4 (Home, Library, Voices,
        // Settings).
        assertTrue(BottomDestination.entries.size in MIN_BOTTOM_DESTINATIONS..MAX_BOTTOM_DESTINATIONS)
    }

    @Test
    fun `every BottomDestination label is non-blank and unique`() {
        assertUniqueNonBlankLabels(BottomDestination.entries.map { it.label })
    }

    @Test
    fun `every VoicesTab label is non-blank and unique`() {
        assertUniqueNonBlankLabels(VoicesTab.entries.map { it.label })
    }

    @Test
    fun `every SettingsRow label is non-blank and unique`() {
        assertUniqueNonBlankLabels(SettingsRow.entries.map { it.label })
    }

    @Test
    fun `no Screen is modeled in more than one of the three nav lists`() {
        // Each screen should live in exactly one of bottom-nav / Voices-tab / Settings-row - overlap
        // would mean the same destination is modeled (and so reachable, and highlighted) twice.
        val overlap =
            (bottomNavScreens() intersect voicesTabScreens()) +
                (bottomNavScreens() intersect settingsRowScreens()) +
                (voicesTabScreens() intersect settingsRowScreens())
        assertTrue("Screen(s) modeled in more than one nav list: $overlap", overlap.isEmpty())
    }

    @Test
    fun `every Screen resolves to a bottom destination for nav-bar highlighting`() {
        // Screen.parentBottomDestination() is an exhaustive `when` over Screen - a value it doesn't
        // handle is a COMPILE error, not a silent gap, but this still exercises every value so the
        // exhaustiveness guarantee is checked here too, not only trusted.
        Screen.entries.forEach { screen -> screen.parentBottomDestination() }
    }

    private fun assertUniqueNonBlankLabels(labels: List<String>) {
        assertTrue("A label is blank: $labels", labels.all { it.isNotBlank() })
        val duplicates = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("Duplicate labels: $duplicates", duplicates.isEmpty())
    }

    private companion object {
        const val MIN_BOTTOM_DESTINATIONS = 3
        const val MAX_BOTTOM_DESTINATIONS = 5
    }
}
