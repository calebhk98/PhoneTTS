package com.phonetts.app

import com.phonetts.app.ui.NAV_ITEMS
import com.phonetts.app.ui.NavItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the drawer navigation graph against the exact bug class the owner asked for: a new
 * [Screen] value added to the enum but never wired into anything that can reach it.
 *
 * This is DERIVED from the code, not a hand-copied list: it walks [Screen.entries] and
 * [NAV_ITEMS] directly, so adding a destination automatically comes under test with no edit
 * here. The only thing that ever needs a deliberate edit is [NON_DRAWER_ENTRY_POINTS] below, and
 * only for a screen that is genuinely reached some other way than the drawer.
 */
class NavigationReachabilityTest {

    /**
     * [Screen] values that are legitimately reachable WITHOUT appearing in [NAV_ITEMS] - each one
     * documented with exactly how a user gets there. Adding to this list is a deliberate, visible
     * act (that's the point - see MainActivity's `AppNav`): anything not in [NAV_ITEMS] and not
     * explicitly allow-listed here fails this test.
     */
    private val nonDrawerEntryPoints: Map<Screen, String> = mapOf(
        Screen.ONBOARDING to
            "First-launch-only walkthrough (MainActivity.AppNav): shown before the drawer exists " +
                "at all, never itself a drawer destination.",
        Screen.MAIN to
            "The app's start destination (MainActivity.AppNav) once onboarding is done - it is ALSO " +
                "in NAV_ITEMS (\"Home\"), listed here only to document why it's an entry point too.",
    )

    private fun drawerScreens(): Set<Screen> =
        NAV_ITEMS.filterIsInstance<NavItem.ToScreen>().map { it.screen }.toSet()

    @Test
    fun `every Screen is reachable from the drawer or a documented entry point`() {
        val reachable = drawerScreens() + nonDrawerEntryPoints.keys
        val unreachable = Screen.entries.filterNot { it in reachable }
        assertTrue(
            "Screen(s) $unreachable are neither in NAV_ITEMS nor in the documented " +
                "nonDrawerEntryPoints allow-list in this test - a user (and this test) has no way " +
                "to reach them. Either add a NAV_ITEMS entry or, if it's a genuine non-drawer entry " +
                "point (like ONBOARDING), add it to nonDrawerEntryPoints with a comment explaining how.",
            unreachable.isEmpty(),
        )
    }

    @Test
    fun `documented entry points still name real Screen values`() {
        // Catches the mirror-image mistake: a Screen removed from the enum but left dangling in
        // the allow-list above (the map key type already prevents typos; this just keeps the map
        // honest if Screen ever loses a value some other way).
        val entries = Screen.entries.toSet()
        assertTrue(nonDrawerEntryPoints.keys.all { it in entries })
    }

    @Test
    fun `every NAV_ITEMS label is non-blank`() {
        NAV_ITEMS.forEach { item ->
            assertTrue("NAV_ITEMS entry '${item.label}' has a blank label", item.label.isNotBlank())
        }
    }

    @Test
    fun `every NAV_ITEMS label is unique`() {
        val labels = NAV_ITEMS.map { it.label }
        val duplicates = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("NAV_ITEMS has duplicate labels: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every NAV_ITEMS ToScreen entry points at a real Screen value`() {
        // Kotlin's type system already forces NavItem.ToScreen.screen to be a Screen, so this can
        // never fail at runtime for a compiled build - kept as an explicit, readable assertion of
        // the contract (per the mission) rather than trusting that invariant silently.
        val validScreens = Screen.entries.toSet()
        NAV_ITEMS.filterIsInstance<NavItem.ToScreen>().forEach { item ->
            val message = "NAV_ITEMS entry '${item.label}' points at ${item.screen}, not a real Screen"
            assertTrue(message, item.screen in validScreens)
        }
    }

    @Test
    fun `no Screen is drawer-reachable AND undocumented as MAIN is`() {
        // Sanity check on the allow-list itself: MAIN is deliberately double-covered (both
        // NAV_ITEMS and nonDrawerEntryPoints); every OTHER documented entry point should be one
        // NOT already covered by the drawer, or the allow-list entry is dead weight.
        val redundant = nonDrawerEntryPoints.keys.filter { it in drawerScreens() && it != Screen.MAIN }
        val message = "Redundant nonDrawerEntryPoints (already in NAV_ITEMS): $redundant"
        assertEquals(message, emptyList<Screen>(), redundant)
    }
}
