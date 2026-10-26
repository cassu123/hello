package org.hello.reader.feature.settings

import org.hello.reader.core.model.ReaderFontFamily
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.core.model.ReaderThemePreset
import org.hello.reader.core.model.ReadingLayoutMode
import org.hello.reader.core.model.WcagContrastValidator
import org.hello.reader.feature.onboarding.url.isRiverSongAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.hello.reader.ui.components.cardNameRes
import org.junit.Test

class SettingsPresentationTest {

    @Test
    fun `hex values are normalized before saving`() {
        assertEquals("#FAFAF2", " fafaf2 ".normalizedHex())
        assertEquals("#1B1C20", "#1b1c20".normalizedHex())
    }

    @Test
    fun `custom theme counts as existing once any custom field changes or it is selected`() {
        assertFalse(ReaderSettings().hasCustomTheme)
        assertTrue(ReaderSettings(themePreset = ReaderThemePreset.CUSTOM).hasCustomTheme)
        assertTrue(ReaderSettings(customBackgroundHex = "#1B1C20").hasCustomTheme)
        assertTrue(ReaderSettings(customThemeName = "Evening tea").hasCustomTheme)
    }

    @Test
    fun `preset cards use short names`() {
        assertEquals(org.hello.reader.R.string.theme_sepia, ReaderThemePreset.WARM_SEPIA.cardNameRes())
        assertEquals(org.hello.reader.R.string.theme_black, ReaderThemePreset.OLED_BLACK.cardNameRes())
        assertEquals(org.hello.reader.R.string.theme_solarized, ReaderThemePreset.SOLARIZED_DARK.cardNameRes())
    }

    private fun summary(settings: ReaderSettings) = fontAndLayoutSummary(settings, "Pages", "Scroll", "Your font")

    @Test
    fun `font and layout summary names the font and the mode`() {
        assertEquals("Literata · Pages", summary(ReaderSettings()))
        assertEquals(
            "Atkinson · Scroll",
            summary(
                ReaderSettings(fontFamily = ReaderFontFamily.ATKINSON_HYPERLEGIBLE, layoutMode = ReadingLayoutMode.CONTINUOUS_SCROLL),
            ),
        )
        assertEquals("Your font · Pages", summary(ReaderSettings(fontFamily = ReaderFontFamily.CUSTOM)))
    }

    @Test
    fun `speed is shown with two decimals`() {
        assertEquals("1.00×", formatSpeed(1f))
        assertEquals("1.25×", formatSpeed(1.25f))
    }

    @Test
    fun `contrast label grades the ratio`() {
        assertEquals("21.0:1 · AAA", contrastLabel(WcagContrastValidator.evaluate("#FFFFFF", "#000000")!!, "Low contrast"))
        assertTrue(contrastLabel(WcagContrastValidator.evaluate("#FFFFFF", "#AAAAAA")!!, "Low contrast").endsWith("Low contrast"))
    }

    @Test
    fun `river song easter egg only triggers on her addresses`() {
        assertTrue(isRiverSongAddress("https://sweetie.example.com"))
        assertTrue(isRiverSongAddress("RiverSong.local"))
        assertFalse(isRiverSongAddress("storyteller.example.com"))
    }
}
