package com.cinedeck.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.cinedeck.app.R

/** ReelDJ "Neon Booth" palette. */
object C {
    val Bg = Color(0xFF08080B)
    val Surface = Color(0xFF121218)
    val Surface2 = Color(0xFF1B1B24)
    val Line = Color(0xFF33333F)
    val Accent = Color(0xFFFF3D9A)   // magenta: focus, primary actions, active tab
    val AccentDim = Color(0xFF7A1E4B)
    val Cyan = Color(0xFF3DE0FF)     // secondary: progress, tags
    val Text = Color(0xFFF5F5F7)
    val Muted = Color(0xFFA0A0AE)
    val Gold = Color(0xFFFFC94D)
    val New = Color(0xFFFF5470)
}

/** Space Grotesk for the brand voice: logo, titles, buttons, tabs. */
val Display = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_medium, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
    Font(R.font.space_grotesk_bold, FontWeight.Black),
)

/** True on Android TV / large landscape screens: side rail, bigger focus targets. */
val LocalIsTv = staticCompositionLocalOf { false }
val LocalWide = staticCompositionLocalOf { false }

private val base = Typography()
private val type = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Display),
    displayMedium = base.displayMedium.copy(fontFamily = Display),
    displaySmall = base.displaySmall.copy(fontFamily = Display),
    headlineLarge = base.headlineLarge.copy(fontFamily = Display),
    headlineMedium = base.headlineMedium.copy(fontFamily = Display),
    headlineSmall = base.headlineSmall.copy(fontFamily = Display),
    titleLarge = base.titleLarge.copy(fontFamily = Display),
    titleMedium = base.titleMedium.copy(fontFamily = Display),
    labelLarge = base.labelLarge.copy(fontFamily = Display),
)

/** Headline style used by screen titles and section headers. */
val Heading = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, color = C.Text)

@Composable
fun CineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = Color.White,
            secondary = C.Cyan,
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
            outline = C.Line,
        ),
        typography = type,
        content = content,
    )
}
