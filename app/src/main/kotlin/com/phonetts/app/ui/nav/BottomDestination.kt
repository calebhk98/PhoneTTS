package com.phonetts.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.phonetts.app.Screen

/**
 * The four top-level destinations in the bottom [androidx.compose.material3.NavigationBar]
 * (replaces the hamburger-drawer-for-everything nav - Material 3 treats a nav bar as the right
 * choice and a drawer as the worse option on a phone/compact width; see
 * m3.material.io/components/navigation-bar/guidelines and
 * developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns).
 *
 * Each entry is a PLACE the user goes, never a one-shot action - "Import model from folder" used to
 * be a drawer row that ran an action instead of navigating anywhere, which is exactly the "action in
 * the destination list" mistake this model rules out by construction. It now lives on the Downloaded
 * models screen ([com.phonetts.app.manage.ModelManagementScreen]) as an `ExtendedFloatingActionButton`.
 *
 * Deliberately a fixed list of 4 - Material 3's recommended range for a phone bottom bar is 3-5 -
 * unlike the legacy drawer list this is not meant to grow; anything else lives one level down as a
 * [VoicesTab] (inside Voices) or a [SettingsRow] (under Settings).
 */
enum class BottomDestination(val label: String, val icon: ImageVector, val screen: Screen) {
    HOME("Home", Icons.Filled.AutoStories, Screen.MAIN),
    LIBRARY("Library", Icons.AutoMirrored.Filled.LibraryBooks, Screen.LIBRARY),
    VOICES("Voices", Icons.Filled.RecordVoiceOver, Screen.VOICES),
    SETTINGS("Settings", Icons.Filled.Settings, Screen.SETTINGS),
}
