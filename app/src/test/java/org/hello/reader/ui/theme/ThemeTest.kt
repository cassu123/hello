package org.hello.reader.ui.theme

import androidx.compose.ui.graphics.Color
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.core.model.ReaderThemePreset
import org.junit.Assert.assertEquals
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    private fun buildThemeColorScheme(settings: ReaderSettings) =
        buildColorScheme(settings.toThemeSeed())

    @Test
    fun `parseColorOrNull parses valid 6 and 8 digit hex colors`() {
        assertEquals(Color(0xFFFFFFFF), parseColorOrNull("#FFFFFF"))
        assertEquals(Color(0xFF000000), parseColorOrNull("#000000"))
        assertEquals(Color(0xFF3D5AFE), parseColorOrNull("3D5AFE"))
        assertEquals(Color(0x80FF0000), parseColorOrNull("#80FF0000"))

        assertNull(parseColorOrNull(null))
        assertNull(parseColorOrNull(""))
        assertNull(parseColorOrNull("invalid"))
        assertNull(parseColorOrNull("#12345"))
    }

    @Test
    fun `buildThemeColorScheme respects day preset colors`() {
        val settings = ReaderSettings(themePreset = ReaderThemePreset.WARM_SEPIA)
        val scheme = buildThemeColorScheme(settings)

        assertEquals(parseColorOrNull("#F4ECD8"), scheme.background)
        assertEquals(parseColorOrNull("#3B2E1E"), scheme.onBackground)
        // The raw amber highlight is too light for text and buttons on sepia, so it is darkened.
        assertNotEquals(parseColorOrNull("#D9A441"), scheme.primary)
        assertTrue(contrastRatio(scheme.primary, scheme.background) >= 4.5)
        assertTrue(scheme.primary.luminance() < parseColorOrNull("#D9A441")!!.luminance())
    }

    @Test
    fun `buildThemeColorScheme respects OLED black night preset`() {
        val settings = ReaderSettings(themePreset = ReaderThemePreset.OLED_BLACK)
        val scheme = buildThemeColorScheme(settings)

        assertEquals(Color.Black, scheme.background)
        assertEquals(parseColorOrNull("#E6E6E6"), scheme.onBackground)
        // Indigo on pure black is under 4.5:1, so the accent is lightened.
        assertTrue(contrastRatio(scheme.primary, Color.Black) >= 4.5)
        assertTrue(scheme.primary.luminance() > parseColorOrNull("#3D5AFE")!!.luminance())
    }

    @Test
    fun `buildThemeColorScheme applies custom theme with custom day or night mode`() {
        val customNight = ReaderSettings(
            themePreset = ReaderThemePreset.CUSTOM,
            customThemeName = "Midnight Velvet",
            customIsNight = true,
            customBackgroundHex = "#0D1117",
            customForegroundHex = "#C9D1D9",
            customHighlightHex = "#58A6FF",
        )
        val nightScheme = buildThemeColorScheme(customNight)
        assertEquals(parseColorOrNull("#0D1117"), nightScheme.background)
        assertEquals(parseColorOrNull("#C9D1D9"), nightScheme.onBackground)
        assertEquals(parseColorOrNull("#58A6FF"), nightScheme.primary)

        val customDay = customNight.copy(
            customIsNight = false,
            customBackgroundHex = "#F6F8FA",
            customForegroundHex = "#24292F",
            customHighlightHex = "#0969DA",
        )
        val dayScheme = buildThemeColorScheme(customDay)
        assertEquals(parseColorOrNull("#F6F8FA"), dayScheme.background)
        assertEquals(parseColorOrNull("#24292F"), dayScheme.onBackground)
        assertEquals(parseColorOrNull("#0969DA"), dayScheme.primary)
    }

    @Test
    fun `accessible accents are left alone`() {
        // #58A6FF on #0D1117 and #0969DA on #F6F8FA (custom theme test above) already pass 4.5:1.
        val paper = buildColorScheme(PaperSeed)
        assertEquals(PaperSeed.highlight, paper.primary)
    }

    @Test
    fun `secondary text and surfaces stay readable for every preset`() {
        ReaderThemePreset.entries.forEach { preset ->
            val scheme = buildThemeColorScheme(ReaderSettings(themePreset = preset))
            assertTrue("$preset onSurfaceVariant", contrastRatio(scheme.onSurfaceVariant, scheme.background) >= 4.5)
            assertTrue("$preset onSurface on container", contrastRatio(scheme.onSurface, scheme.surfaceContainerHigh) >= 4.5)
            assertTrue("$preset onPrimary", contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5)
        }
    }

    @Test
    fun `invalid hex falls back instead of crashing`() {
        assertNull(parseColorOrNull("#GGGGGG"))
    }
}
