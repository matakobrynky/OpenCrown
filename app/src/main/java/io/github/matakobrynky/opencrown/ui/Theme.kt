package io.github.matakobrynky.opencrown.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One full set of interface colours. */
data class Scheme(
    val dark: Boolean,
    val bg: Color,
    val surface1: Color,     // cards
    val surface2: Color,     // badges, menus, dialogs
    val soft: Color,         // quiet panels ("How to connect")
    val line: Color,
    val ink: Color,          // main text
    val dim: Color,          // secondary text
    val pill: Color,         // "+ Add" pill background
    val linked: Color,       // outline of a connected hub
    val canvas: Color,       // driving screen background
    val controlFill: Color,  // inside of joysticks, sliders, buttons
    val grid: Color,         // dot grid on the driving screen
    val palette: List<Color>,
)

val DarkScheme = Scheme(
    dark = true,
    bg = Color(0xFF0B0E13), surface1 = Color(0xFF141922), surface2 = Color(0xFF1C222D),
    soft = Color(0xFF1B191B), line = Color(0xFF2A3240),
    ink = Color(0xFFE9EEF5), dim = Color(0xFF8A94A6), pill = Color(0xFF1C222D),
    linked = Color(0xFF2FC56E), canvas = Color(0xFF0E1218), controlFill = Color(0xFF10151D),
    grid = Color(0x0DFFFFFF),
    palette = listOf(
        Color(0xFF00D1FF), Color(0xFFFF8A00), Color(0xFF7CFF4F), Color(0xFFFF3DCB),
        Color(0xFFFFD60A), Color(0xFF9B7BFF), Color(0xFFFF4D4D), Color(0xFFE6EDF5),
    ),
)

val LightScheme = Scheme(
    dark = false,
    bg = Color(0xFFEDE4D9), surface1 = Color(0xFFE6D5C2), surface2 = Color(0xFFF5EEE5),
    soft = Color(0xFFE6D5C2), line = Color(0xFFD3BFA8),
    ink = Color(0xFF1C222D), dim = Color(0xFF6F6557), pill = Color(0xFFE2CDB5),
    linked = Color(0xFF5BB8F0), canvas = Color(0xFFE6D5C2), controlFill = Color(0xFFF6EFE6),
    grid = Color(0x221C222D),
    // same hues, deep enough to read on beige
    palette = listOf(
        Color(0xFF0098CC), Color(0xFFF07800), Color(0xFF3FA81E), Color(0xFFD62BAE),
        Color(0xFFC99A00), Color(0xFF7A5CF0), Color(0xFFE03A3A), Color(0xFF3A4250),
    ),
)

enum class ThemeMode(val label: String) { SYSTEM("Follow phone"), LIGHT("Light"), DARK("Dark") }

/** Current colours; read through the getters below so screens recolour when it changes. */
object Ui {
    var scheme by mutableStateOf(DarkScheme)
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** Editor sections the user has minimised (remembered between sessions). */
    val collapsed = androidx.compose.runtime.mutableStateListOf<String>()

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val s = p.getString("theme", null)
        mode = ThemeMode.entries.firstOrNull { it.name == s } ?: ThemeMode.SYSTEM
        collapsed.clear(); collapsed.addAll(p.getStringSet("collapsed", emptySet()) ?: emptySet())
    }

    fun toggleCollapsed(ctx: Context, key: String) {
        if (key in collapsed) collapsed.remove(key) else collapsed.add(key)
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putStringSet("collapsed", collapsed.toSet()).apply()
    }

    fun setMode(ctx: Context, m: ThemeMode) {
        mode = m
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", m.name).apply()
    }
}

val Bg: Color get() = Ui.scheme.bg
val Surface1: Color get() = Ui.scheme.surface1
val Surface2: Color get() = Ui.scheme.surface2
val Soft: Color get() = Ui.scheme.soft
val Line: Color get() = Ui.scheme.line
val Ink: Color get() = Ui.scheme.ink
val TextDim: Color get() = Ui.scheme.dim
val Pill: Color get() = Ui.scheme.pill

/** Brand orange: buttons, hub names, links. */
val Accent = Color(0xFFFF6A00)
val Orange = Color(0xFFFF8A00)
val Cyan = Color(0xFF00D1FF)
val Danger = Color(0xFFE5383B)
val Ok = Color(0xFF2FC56E)

/** The driving canvas and its controls follow the theme too. */
val CanvasBg: Color get() = Ui.scheme.canvas
val ControlFill: Color get() = Ui.scheme.controlFill
val GridDot: Color get() = Ui.scheme.grid
val Linked: Color get() = Ui.scheme.linked
/** Background of the raw Bluetooth console (always dark). */
val ConsoleBg = Color(0xFF0E1218)

val Palette: List<Color> get() = Ui.scheme.palette

fun paletteColor(i: Int) = Palette[((i % Palette.size) + Palette.size) % Palette.size]

private val Mono = FontFamily.Monospace

@Composable
fun MkTheme(content: @Composable () -> Unit) {
    val dark = when (Ui.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val s = if (dark) DarkScheme else LightScheme
    if (Ui.scheme !== s) Ui.scheme = s
    val colors = if (dark) darkColorScheme(
        primary = Accent, onPrimary = Color.Black,
        secondary = Accent, onSecondary = Color.Black,
        background = s.bg, onBackground = s.ink,
        surface = s.surface1, onSurface = s.ink,
        surfaceVariant = s.surface2, onSurfaceVariant = s.dim,
        surfaceContainer = s.surface1, surfaceContainerHigh = s.surface2, surfaceContainerHighest = s.surface2,
        surfaceContainerLow = s.surface1, surfaceContainerLowest = s.bg,
        secondaryContainer = Color(0xFF4A2A10), onSecondaryContainer = Color(0xFFFFB27A),
        outline = s.line, outlineVariant = s.line, error = Danger,
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.Black,
        secondary = Accent, onSecondary = Color.Black,
        background = s.bg, onBackground = s.ink,
        surface = s.surface1, onSurface = s.ink,
        surfaceVariant = s.surface2, onSurfaceVariant = s.dim,
        surfaceContainer = s.surface2, surfaceContainerHigh = s.surface2, surfaceContainerHighest = s.surface1,
        surfaceContainerLow = s.surface2, surfaceContainerLowest = s.bg,
        secondaryContainer = Color(0xFFFFD9BC), onSecondaryContainer = Color(0xFF7A3000),
        outline = s.line, outlineVariant = s.line, error = Danger,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}
