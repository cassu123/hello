package org.hello.reader.core.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Serializable
enum class ReadingLayoutMode(val displayName: String) {
    PAGED("Paginated"),
    CONTINUOUS_SCROLL("Continuous Scroll"),
}

@Serializable
enum class ReaderFontFamily(val displayName: String) {
    ATKINSON_HYPERLEGIBLE("Atkinson Hyperlegible"),
    LITERATA("Literata"),
    BOOKERLY("Bookerly"),
    OPEN_DYSLEXIC("OpenDyslexic"),
    CUSTOM("Custom Sideloaded Font"),
}

@Serializable
enum class DarkModePreference(val displayName: String) {
    LIGHT("Light"),
    DEVICE("Device"),
    DARK("Dark"),
}

@Serializable
enum class PageTurnAnimation(val displayName: String) {
    CURL("Flip"),
    SLIDE("Slide"),
    COVER("Cover"),
    NONE("None"),
}

@Serializable
enum class TwoPageViewMode(val displayName: String) {
    AUTO("Auto"),
    OFF("Off"),
    ON("On"),
}

@Serializable
data class ReadingProfile(
    val id: String,
    val name: String,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.LITERATA,
    val fontScalePercent: Int = 100,
    val boldWeight: Int = 0,
    val lineHeightMultiplier: Float = 1.5f,
    val letterSpacingEm: Float = 0.0f,
    val marginHorizontalDp: Int = 24,
    val layoutMode: ReadingLayoutMode = ReadingLayoutMode.PAGED,
    val textJustified: Boolean = true,
) {
    fun matches(settings: ReaderSettings): Boolean =
        fontFamily == settings.fontFamily &&
            fontScalePercent == settings.fontScalePercent &&
            boldWeight == settings.boldWeight &&
            lineHeightMultiplier == settings.lineHeightMultiplier &&
            letterSpacingEm == settings.letterSpacingEm &&
            marginHorizontalDp == settings.marginHorizontalDp &&
            layoutMode == settings.layoutMode &&
            textJustified == settings.textJustified
}

val DefaultReadingProfiles = listOf(
    ReadingProfile(
        id = "preset_default",
        name = "Default",
        fontFamily = ReaderFontFamily.LITERATA,
        fontScalePercent = 100,
        lineHeightMultiplier = 1.5f,
        marginHorizontalDp = 24,
        layoutMode = ReadingLayoutMode.PAGED,
    ),
    ReadingProfile(
        id = "preset_large",
        name = "Large Print",
        fontFamily = ReaderFontFamily.ATKINSON_HYPERLEGIBLE,
        fontScalePercent = 130,
        boldWeight = 1,
        lineHeightMultiplier = 1.6f,
        marginHorizontalDp = 16,
        layoutMode = ReadingLayoutMode.PAGED,
    ),
    ReadingProfile(
        id = "preset_compact",
        name = "Compact",
        fontFamily = ReaderFontFamily.BOOKERLY,
        fontScalePercent = 90,
        boldWeight = 0,
        lineHeightMultiplier = 1.35f,
        marginHorizontalDp = 32,
        layoutMode = ReadingLayoutMode.PAGED,
    ),
    ReadingProfile(
        id = "preset_scroll",
        name = "Continuous",
        fontFamily = ReaderFontFamily.LITERATA,
        fontScalePercent = 105,
        lineHeightMultiplier = 1.55f,
        marginHorizontalDp = 20,
        layoutMode = ReadingLayoutMode.CONTINUOUS_SCROLL,
    ),
)

@Serializable
enum class ReaderThemePreset(
    val displayName: String,
    val backgroundHex: String,
    val foregroundHex: String,
    val highlightHex: String,
    val isNight: Boolean,
) {
    PAPER("Paper", "#FBFAF7", "#18181B", "#2B4FB8", isNight = false),
    GREEN_TEA("Green Tea", "#EDF3E8", "#1A2E1A", "#4A7C59", isNight = false),
    WARM_SEPIA("Warm Sepia", "#F4ECD8", "#3B2E1E", "#D9A441", isNight = false),
    SOFT_CREAM("Soft Cream", "#FAF8F2", "#222222", "#E6B800", isNight = false),
    GRAY("Gray", "#E4E4E1", "#1F1F22", "#3F6FB5", isNight = false),
    PEACH("Peach Dream", "#FEE7D9", "#1A1A2E", "#D66C59", isNight = false),
    ARCTIC("Arctic", "#DCE6F0", "#1A1A2E", "#4A7AA8", isNight = false),
    NIGHT("Night", "#1B1C20", "#E3E1DC", "#A9BDFF", isNight = true),
    NORDIC_MIST("Nordic Mist", "#1E242B", "#E2E8F0", "#64B5F6", isNight = true),
    SLATE_GRAY("Slate Gray", "#263238", "#ECEFF1", "#4FC3F7", isNight = true),
    SOLARIZED_DARK("Solarized Dark", "#002B36", "#93A1A1", "#B58900", isNight = true),
    OLED_BLACK("Pure OLED Black", "#000000", "#E6E6E6", "#3D5AFE", isNight = true),
    RIVER_SONG("River Song", "#0F1316", "#E0E6ED", "#96CBFF", isNight = true),
    CYBERPUNK("Cyberpunk", "#050505", "#F0F0F0", "#E8FF00", isNight = true),
    DUNE("Dune", "#0A0804", "#E8DFC5", "#DEB651", isNight = true),
    E_INK("E-Ink", "#FFFFFF", "#000000", "#333333", isNight = false),
    CUSTOM("Custom Theme", "#FFFFFF", "#111111", "#FFE066", isNight = false),
}

@Serializable
data class ReaderSettings(
    val fontFamily: ReaderFontFamily = ReaderFontFamily.LITERATA,
    val customFontUri: String? = null,
    val fontScalePercent: Int = 100,         // 80% .. 250%
    val boldWeight: Int = 0,                 // 0 (normal) .. 3 (extra bold)
    val lineHeightMultiplier: Float = 1.5f,  // 1.0x .. 2.5x
    val letterSpacingEm: Float = 0.0f,       // -0.05f .. 0.25f em
    val paragraphSpacingEm: Float = 0.8f,
    val marginHorizontalDp: Int = 24,
    val textJustified: Boolean = true,
    val layoutMode: ReadingLayoutMode = ReadingLayoutMode.PAGED,
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.CURL,
    val twoPageViewMode: TwoPageViewMode = TwoPageViewMode.AUTO,
    val themePreset: ReaderThemePreset = ReaderThemePreset.PAPER,
    val darkModePreference: DarkModePreference = DarkModePreference.DEVICE,
    val selectedLightTheme: String = ReaderThemePreset.PAPER.name,
    val selectedDarkTheme: String = ReaderThemePreset.NIGHT.name,
    val customThemeName: String = "Custom Theme",
    val customIsNight: Boolean = false,
    val customBackgroundHex: String = "#FFFFFF",
    val customForegroundHex: String = "#111111",
    val customHighlightHex: String = "#FFE066",
    val readingProfiles: List<ReadingProfile> = DefaultReadingProfiles,
    val activeProfileId: String? = "preset_default",
    val playbackSpeed: Float = 1.0f,         // 0.5x .. 3.5x in 0.05 increments
    val smartRewindEnabled: Boolean = true,
    /** When false the app chrome uses Paper / Night (following the system) and only the reading page is themed. */
    val appUsesReaderTheme: Boolean = true,
    val skipBackSeconds: Int = 15,
    val skipForwardSeconds: Int = 30,
    val volumeButtonsTurnPages: Boolean = org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk,
    val eInkOptimized: Boolean = org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk,
    val keepPlayingAfterAppClose: Boolean = false,
    val alwaysShowBackToReadingPosition: Boolean = false,
    val brightnessFollowSystem: Boolean = true,
    val brightnessPercent: Int = 100,
    val fullRefreshIntervalPages: Int = if (org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk) 10 else 0,
) {
    val effectiveBackgroundHex: String
        get() = if (themePreset == ReaderThemePreset.CUSTOM) customBackgroundHex else themePreset.backgroundHex

    val effectiveForegroundHex: String
        get() = if (themePreset == ReaderThemePreset.CUSTOM) customForegroundHex else themePreset.foregroundHex

    val effectiveHighlightHex: String
        get() = if (themePreset == ReaderThemePreset.CUSTOM) customHighlightHex else themePreset.highlightHex

    val effectiveThemeName: String
        get() = if (themePreset == ReaderThemePreset.CUSTOM) customThemeName.ifBlank { "Custom Theme" } else themePreset.displayName

    val isNightTheme: Boolean
        get() = if (themePreset == ReaderThemePreset.CUSTOM) customIsNight else themePreset.isNight

    val hasCustomTheme: Boolean
        get() = themePreset == ReaderThemePreset.CUSTOM ||
            (customThemeName.isNotBlank() && customThemeName != "Custom Theme") ||
            customBackgroundHex != "#FFFFFF" || customForegroundHex != "#111111"

    fun withDayNightResolved(isSystemInDark: Boolean): ReaderSettings {
        val shouldUseDark = when (darkModePreference) {
            DarkModePreference.LIGHT -> false
            DarkModePreference.DARK -> true
            DarkModePreference.DEVICE -> isSystemInDark
        }
        val targetName = if (shouldUseDark) selectedDarkTheme else selectedLightTheme
        val preset = if (targetName.equals("CUSTOM", ignoreCase = true)) {
            ReaderThemePreset.CUSTOM
        } else {
            ReaderThemePreset.entries.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                ?: if (shouldUseDark) ReaderThemePreset.NIGHT else ReaderThemePreset.PAPER
        }
        return copy(themePreset = preset)
    }

    fun withPresetSelected(preset: ReaderThemePreset, isSystemInDark: Boolean = false): ReaderSettings {
        val targetIsNight = if (preset == ReaderThemePreset.CUSTOM) customIsNight else preset.isNight
        val currentlyInDark = when (darkModePreference) {
            DarkModePreference.LIGHT -> false
            DarkModePreference.DARK -> true
            DarkModePreference.DEVICE -> isSystemInDark
        }
        val newPreference = if (targetIsNight != currentlyInDark) {
            if (targetIsNight) DarkModePreference.DARK else DarkModePreference.LIGHT
        } else {
            darkModePreference
        }
        return if (targetIsNight) {
            copy(
                themePreset = preset,
                selectedDarkTheme = if (preset == ReaderThemePreset.CUSTOM) "CUSTOM" else preset.name,
                darkModePreference = newPreference,
            )
        } else {
            copy(
                themePreset = preset,
                selectedLightTheme = if (preset == ReaderThemePreset.CUSTOM) "CUSTOM" else preset.name,
                darkModePreference = newPreference,
            )
        }
    }

    fun removeCustomReadingProfile(profileId: String): ReaderSettings = copy(
        readingProfiles = readingProfiles.filterNot { it.id == profileId },
        activeProfileId = if (activeProfileId == profileId) null else activeProfileId,
    )

    fun applyProfile(profile: ReadingProfile): ReaderSettings = copy(
        activeProfileId = profile.id,
        fontFamily = profile.fontFamily,
        fontScalePercent = profile.fontScalePercent,
        boldWeight = profile.boldWeight,
        lineHeightMultiplier = profile.lineHeightMultiplier,
        letterSpacingEm = profile.letterSpacingEm,
        marginHorizontalDp = profile.marginHorizontalDp,
        layoutMode = profile.layoutMode,
        textJustified = profile.textJustified,
    )
}

/**
 * Real-time WCAG 2.1 contrast ratio calculator & validator for custom hex themes
 * (`TASK_BACKLOG.md` Section 4.2).
 */
object WcagContrastValidator {

    data class ContrastReport(
        val ratio: Double,
        val passesAA: Boolean,
        val passesAAA: Boolean,
    )

    fun evaluate(backgroundHex: String, foregroundHex: String): ContrastReport? {
        val bgRgb = parseHexRgb(backgroundHex) ?: return null
        val fgRgb = parseHexRgb(foregroundHex) ?: return null
        val l1 = relativeLuminance(bgRgb.first, bgRgb.second, bgRgb.third)
        val l2 = relativeLuminance(fgRgb.first, fgRgb.second, fgRgb.third)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        val rawRatio = (lighter + 0.05) / (darker + 0.05)
        val rounded = (rawRatio * 100.0).roundToInt() / 100.0
        return ContrastReport(
            ratio = rounded,
            passesAA = rawRatio >= 4.5,
            passesAAA = rawRatio >= 7.0,
        )
    }

    private fun parseHexRgb(hex: String): Triple<Int, Int, Int>? {
        val cleaned = hex.trim().removePrefix("#")
        val normalized = when (cleaned.length) {
            6 -> cleaned
            8 -> cleaned.substring(2) // strip alpha if ARGB
            else -> return null
        }
        val r = normalized.substring(0, 2).toIntOrNull(16) ?: return null
        val g = normalized.substring(2, 4).toIntOrNull(16) ?: return null
        val b = normalized.substring(4, 6).toIntOrNull(16) ?: return null
        return Triple(r, g, b)
    }

    private fun relativeLuminance(r: Int, g: Int, b: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }
}
