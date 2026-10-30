package com.cinedeck.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object C {
    val Bg = Color(0xFF0B0D10)
    val Surface = Color(0xFF15181D)
    val Surface2 = Color(0xFF1E2229)
    val Accent = Color(0xFF00D49B)
    val AccentDim = Color(0xFF00835F)
    val Text = Color(0xFFF2F4F7)
    val Muted = Color(0xFF9AA3AF)
    val Gold = Color(0xFFF5C518)
    val New = Color(0xFFFF4D6D)
}

/** True on Android TV / large landscape screens: side rail, bigger focus targets. */
val LocalIsTv = staticCompositionLocalOf { false }
val LocalWide = staticCompositionLocalOf { false }

@Composable
fun CineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = Color.Black,
            secondary = C.Accent,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Surface,
            onSurface = C.Text,
            surfaceVariant = C.Surface2,
            onSurfaceVariant = C.Muted,
            surfaceContainer = C.Surface,
            surfaceContainerHigh = C.Surface2,
            secondaryContainer = C.AccentDim,
            onSecondaryContainer = Color.White,
        ),
        content = content,
    )
}
