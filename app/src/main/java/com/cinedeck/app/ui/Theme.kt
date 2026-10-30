package com.cinedeck.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.cinedeck.app.R

/** One color theme. `second` is the secondary accent (progress bars, tags, icon bars). */
data class Palette(
    val id: String, val name: String,
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val accent: Color, val accentDim: Color, val second: Color,
)

val Palettes = listOf(
    Palette("neon", "Neon Booth", Color(0xFF08080B), Color(0xFF121218), Color(0xFF1B1B24), Color(0xFF33333F), Color(0xFFFF3D9A), Color(0xFF7A1E4B), Color(0xFF3DE0FF)),
    Palette("gold", "Midnight Gold", Color(0xFF0A0C12), Color(0xFF141826), Color(0xFF1C2233), Color(0xFF3A4258), Color(0xFFE2B65C), Color(0xFF6B5428), Color(0xFF8FB6E8)),
    Palette("coral", "Graphite Coral", Color(0xFF111214), Color(0xFF1A1C1F), Color(0xFF232629), Color(0xFF3A3F45), Color(0xFFFF5A4E), Color(0xFF7A2B25), Color(0xFFFFB86B)),
    Palette("ocean", "Ocean", Color(0xFF070B12), Color(0xFF0F1622), Color(0xFF172133), Color(0xFF2A3A55), Color(0xFF3D8BFF), Color(0xFF1C4380), Color(0xFF3DE0C8)),
    Palette("emerald", "Emerald", Color(0xFF07100C), Color(0xFF0F1A15), Color(0xFF17261F), Color(0xFF2A4036), Color(0xFF2BD48A), Color(0xFF156944), Color(0xFFF5D04D)),
    Palette("violet", "Violet", Color(0xFF0B0912), Color(0xFF15121F), Color(0xFF1E1A2C), Color(0xFF3A3352), Color(0xFF9B6BFF), Color(0xFF4A3380), Color(0xFFFF8AD8)),
)

fun paletteFor(id: String) = Palettes.firstOrNull { it.id == id } ?: Palettes.first()

/** The active theme; screens recolor as soon as it changes. */
object ThemeState {
    var palette by androidx.compose.runtime.mutableStateOf(Palettes.first())
}

/** App colors, read from the active theme. */
object C {
    val Bg get() = ThemeState.palette.bg
    val Surface get() = ThemeState.palette.surface
    val Surface2 get() = ThemeState.palette.surface2
    val Line get() = ThemeState.palette.line
    val Accent get() = ThemeState.palette.accent   // focus, primary actions, active tab
    val AccentDim get() = ThemeState.palette.accentDim
    val Cyan get() = ThemeState.palette.second      // secondary: progress, tags
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
