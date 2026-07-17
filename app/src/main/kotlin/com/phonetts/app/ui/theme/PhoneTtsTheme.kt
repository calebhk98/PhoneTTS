package com.phonetts.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

/**
 * App-wide Material3 theme. Follows the system light/dark setting and, on Android 12+, the
 * user's wallpaper-derived dynamic color — falling back to a fixed purple scheme on older
 * devices. Centralized here so no screen picks its own colors (same SSOT spirit as the model
 * layer: one place owns the visual facts).
 */
@Composable
fun PhoneTtsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
