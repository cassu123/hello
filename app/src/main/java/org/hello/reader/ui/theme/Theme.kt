package org.hello.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.hello.reader.core.model.ReaderSettings
import kotlin.math.max
import kotlin.math.min

/** The three colors a theme is defined by, before the app derives a full scheme from them. */
@Immutable
data class ThemeSeed(
    val background: Color,
    val foreground: Color,
    val highlight: Color,
    val isNight: Boolean,
)

/** Built-in app look when the reading theme isn't driving the app. */
val PaperSeed = ThemeSeed(Color(0xFFFBFAF7), Color(0xFF18181B), Color(0xFF2B4FB8), isNight = false)
val NightSeed = ThemeSeed(Color(0xFF1B1C20), Color(0xFFE3E1DC), Color(0xFFA9BDFF), isNight = true)

/** Colors Material 3 has no role for. */
@Immutable
data class HelloExtraColors(
    /** "Online" dots and success text. */
    val success: Color,
    /** Background of the reading page itself (may differ from the app background). */
    val page: Color,
    val onPage: Color,
    /** The theme's raw highlight, for narration and selections (not for text or buttons). */
    val highlight: Color,
    val isNight: Boolean,
)

val LocalHelloColors = staticCompositionLocalOf {
    HelloExtraColors(
        success = Color(0xFF2F7D4F),
        page = PaperSeed.background,
        onPage = PaperSeed.foreground,
        highlight = Color(0xFFEBCB86),
        isNight = false,
    )
}

fun ReaderSettings.toThemeSeed(): ThemeSeed {
    val night = isNightTheme
    val fallback = if (night) NightSeed else PaperSeed
    return ThemeSeed(
        background = parseColorOrNull(effectiveBackgroundHex) ?: fallback.background,
        foreground = parseColorOrNull(effectiveForegroundHex) ?: fallback.foreground,
        highlight = parseColorOrNull(effectiveHighlightHex) ?: fallback.highlight,
        isNight = night,
    )
}

/**
 * Surfaces step by tone from the background (no shadows); the accent is the theme highlight,
 * darkened (day) or lightened (night) until it is readable on the background.
 */
fun buildColorScheme(seed: ThemeSeed): ColorScheme {
    val bg = seed.background
    val fg = seed.foreground
    val night = seed.isNight
    val primary = accessibleAccent(seed.highlight, bg, night)
    val onPrimary = bestOn(primary)
    // Night surfaces lift toward the text color; day surfaces sink toward it.
    fun tone(step: Float) = blend(bg, fg, step)
    val error = if (night) Color(0xFFFFB4A8) else Color(0xFFB3261E)

    val base = if (night) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = blend(bg, primary, if (night) 0.24f else 0.12f),
        onPrimaryContainer = fg,
        inversePrimary = accessibleAccent(seed.highlight, fg, !night),
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = blend(bg, primary, if (night) 0.20f else 0.12f),
        onSecondaryContainer = fg,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = tone(0.08f),
        onTertiaryContainer = fg,
        background = bg,
        onBackground = fg,
        surface = bg,
        onSurface = fg,
        surfaceVariant = tone(0.06f),
        onSurfaceVariant = secondaryText(fg, bg),
        surfaceTint = Color.Transparent,
        inverseSurface = fg,
        inverseOnSurface = bg,
        error = error,
        onError = bestOn(error),
        errorContainer = blend(bg, error, 0.14f),
        onErrorContainer = fg,
        outline = tone(0.28f),
        outlineVariant = tone(if (night) 0.14f else 0.10f),
        scrim = Color.Black,
        surfaceBright = tone(if (night) 0.10f else 0f),
        surfaceDim = tone(if (night) 0f else 0.06f),
        surfaceContainerLowest = if (night) blend(bg, Color.Black, 0.3f) else blend(bg, Color.White, 0.6f),
        surfaceContainerLow = tone(0.025f),
        surfaceContainer = tone(if (night) 0.05f else 0.04f),
        surfaceContainerHigh = tone(if (night) 0.08f else 0.055f),
        surfaceContainerHighest = tone(if (night) 0.12f else 0.08f),
    )
}

/** Secondary text: as far toward the background as it can go while staying at 4.5:1. */
private fun secondaryText(fg: Color, bg: Color): Color {
    var ratio = 0.42f
    while (ratio > 0f) {
        val candidate = blend(fg, bg, ratio)
        if (contrastRatio(candidate, bg) >= 4.5) return candidate
        ratio -= 0.04f
    }
    return fg
}

/** Moves [color] toward black (day) or white (night) until it reaches 4.5:1 against [bg]. */
fun accessibleAccent(color: Color, bg: Color, night: Boolean): Color {
    val target = if (night) Color.White else Color.Black
    var step = 0f
    var candidate = color
    while (contrastRatio(candidate, bg) < 4.5 && step < 1f) {
        step += 0.04f
        candidate = blend(color, target, step)
    }
    return candidate
}

/** Black or white, whichever reads better on [color]. */
fun bestOn(color: Color): Color =
    if (contrastRatio(Color.Black, color) >= contrastRatio(Color.White, color)) Color.Black else Color.White

fun contrastRatio(a: Color, b: Color): Double {
    val l1 = a.luminance().toDouble()
    val l2 = b.luminance().toDouble()
    return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
}

fun parseColorOrNull(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val clean = hex.trim().removePrefix("#")
    val argb = when (clean.length) {
        6 -> clean.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        8 -> clean.toLongOrNull(16)?.toInt()
        else -> null
    } ?: return null
    return Color(argb)
}

fun blend(base: Color, overlay: Color, ratio: Float): Color {
    val r = (base.red * (1f - ratio) + overlay.red * ratio).coerceIn(0f, 1f)
    val g = (base.green * (1f - ratio) + overlay.green * ratio).coerceIn(0f, 1f)
    val b = (base.blue * (1f - ratio) + overlay.blue * ratio).coerceIn(0f, 1f)
    return Color(red = r, green = g, blue = b, alpha = 1f)
}

/**
 * App theme. With [useReaderTheme] the reading theme colors the whole app; otherwise the app
 * uses Paper or Night following the system dark-mode setting.
 */
@Composable
fun HelloTheme(
    readerSettings: ReaderSettings? = null,
    useReaderTheme: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val effectiveDark = when (readerSettings?.darkModePreference) {
        org.hello.reader.core.model.DarkModePreference.LIGHT -> false
        org.hello.reader.core.model.DarkModePreference.DARK -> true
        else -> darkTheme
    }
    val resolvedSettings = readerSettings?.withDayNightResolved(effectiveDark)
    val readerSeed = resolvedSettings?.toThemeSeed()
    val seed = when {
        readerSeed != null && useReaderTheme -> readerSeed
        effectiveDark -> NightSeed
        else -> PaperSeed
    }
    val colorScheme = remember(seed) { buildColorScheme(seed) }
    val extra = remember(seed, readerSeed) {
        val page = readerSeed ?: seed
        HelloExtraColors(
            success = if (seed.isNight) Color(0xFF7CCB97) else Color(0xFF2F7D4F),
            page = page.background,
            onPage = page.foreground,
            highlight = page.highlight,
            isNight = seed.isNight,
        )
    }
    CompositionLocalProvider(LocalHelloColors provides extra) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HelloTypography,
            shapes = HelloShapes,
            content = content,
        )
    }
}
