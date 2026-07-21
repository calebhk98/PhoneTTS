package com.phonetts.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.phonetts.core.prefs.AppTheme

private val Purple40 = Color(0xFF6650A4)
private val PurpleGrey40 = Color(0xFF625B71)
private val Pink40 = Color(0xFF7D5260)
private val Purple80 = Color(0xFFD0BCFF)
private val PurpleGrey80 = Color(0xFFCCC2DC)
private val Pink80 = Color(0xFFEFB8C8)

private val LightColors =
    lightColorScheme(
        primary = Purple40,
        secondary = PurpleGrey40,
        tertiary = Pink40,
    )

private val DarkColors =
    darkColorScheme(
        primary = Purple80,
        secondary = PurpleGrey80,
        tertiary = Pink80,
    )

// Warm, paper-like palette for long reading — low blue light, low glare. A light-family scheme.
private val SepiaColors =
    lightColorScheme(
        primary = Color(0xFF7A5C2E),
        secondary = Color(0xFF6B5A44),
        tertiary = Color(0xFF8A6D3B),
        background = Color(0xFFF4ECD8),
        surface = Color(0xFFEFE6CE),
        onBackground = Color(0xFF3E3524),
        onSurface = Color(0xFF3E3524),
    )

// Pure-black surfaces so OLED pixels switch off — maximum battery saving in a dark room.
private val TrueBlackColors =
    darkColorScheme(
        primary = Purple80,
        secondary = PurpleGrey80,
        tertiary = Pink80,
        background = Color(0xFF000000),
        surface = Color(0xFF000000),
        onBackground = Color(0xFFE6E6E6),
        onSurface = Color(0xFFE6E6E6),
    )

// Maximum legibility: pure black text on pure white with a single saturated accent.
private val HighContrastColors =
    lightColorScheme(
        primary = Color(0xFF0033CC),
        secondary = Color(0xFF000000),
        tertiary = Color(0xFF0033CC),
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        onSurface = Color(0xFF000000),
    )

/**
 * App-wide Material3 theme. Its color scheme is derived from the user's [AppTheme] choice ([theme]):
 * [AppTheme.SYSTEM] follows the OS light/dark setting and, on Android 12+, the wallpaper-derived
 * dynamic color; the other values are fixed reading/OLED schemes. Centralized here so no screen
 * picks its own colors (same SSOT spirit as the model layer: one place owns the visual facts) and
 * so adding a value to [AppTheme] is the only edit needed to grow the picker.
 */
@Composable
fun PhoneTtsTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colorSchemeFor(theme), content = content)
}

@Composable
private fun colorSchemeFor(theme: AppTheme): ColorScheme =
    when (theme) {
        AppTheme.SYSTEM -> systemColorScheme()
        AppTheme.LIGHT -> LightColors
        AppTheme.DARK -> DarkColors
        AppTheme.SEPIA -> SepiaColors
        AppTheme.TRUE_BLACK -> TrueBlackColors
        AppTheme.HIGH_CONTRAST -> HighContrastColors
    }

// "Follow system" keeps the original behavior: OS light/dark plus Android 12+ dynamic color.
@Composable
private fun systemColorScheme(): ColorScheme {
    val darkTheme = isSystemInDarkTheme()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return if (darkTheme) DarkColors else LightColors
    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
