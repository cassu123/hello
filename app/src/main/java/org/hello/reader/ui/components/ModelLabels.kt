package org.hello.reader.ui.components

import androidx.annotation.StringRes
import org.hello.reader.R
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.core.model.ReaderFontFamily
import org.hello.reader.core.model.ReaderThemePreset

/** Short theme name that fits under a 56dp theme card. */
@StringRes
fun ReaderThemePreset.cardNameRes(): Int = when (this) {
    ReaderThemePreset.PAPER -> R.string.theme_paper
    ReaderThemePreset.GREEN_TEA -> R.string.theme_green_tea
    ReaderThemePreset.WARM_SEPIA -> R.string.theme_sepia
    ReaderThemePreset.SOFT_CREAM -> R.string.theme_cream
    ReaderThemePreset.GRAY -> R.string.theme_gray
    ReaderThemePreset.PEACH -> R.string.theme_peach_dream
    ReaderThemePreset.ARCTIC -> R.string.theme_arctic
    ReaderThemePreset.NIGHT -> R.string.theme_night
    ReaderThemePreset.NORDIC_MIST -> R.string.theme_nordic_mist
    ReaderThemePreset.SLATE_GRAY -> R.string.theme_slate
    ReaderThemePreset.SOLARIZED_DARK -> R.string.theme_solarized
    ReaderThemePreset.OLED_BLACK -> R.string.theme_black
    ReaderThemePreset.RIVER_SONG -> R.string.theme_river_song
    ReaderThemePreset.CYBERPUNK -> R.string.theme_cyberpunk
    ReaderThemePreset.DUNE -> R.string.theme_dune
    ReaderThemePreset.E_INK -> R.string.theme_e_ink
    ReaderThemePreset.CUSTOM -> R.string.theme_custom
}

@StringRes
fun HighlightColor.nameRes(): Int = when (this) {
    HighlightColor.YELLOW -> R.string.highlight_yellow
    HighlightColor.GREEN -> R.string.highlight_green
    HighlightColor.BLUE -> R.string.highlight_blue
    HighlightColor.PINK -> R.string.highlight_pink
}

/**
 * Typeface names are proper names and stay as they are; only the user's own font needs a label.
 * Atkinson Hyperlegible is shortened to fit a chip.
 */
fun ReaderFontFamily.chipName(customLabel: String): String = when (this) {
    ReaderFontFamily.ATKINSON_HYPERLEGIBLE -> "Atkinson"
    ReaderFontFamily.CUSTOM -> customLabel
    else -> displayName
}
