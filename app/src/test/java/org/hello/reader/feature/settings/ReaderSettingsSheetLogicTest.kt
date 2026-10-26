package org.hello.reader.feature.settings

import org.hello.reader.core.model.ReaderThemePreset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsSheetLogicTest {

    @Test
    fun `letter spacing snaps to hundredths and clamps to the supported range`() {
        assertEquals(0.03f, snapLetterSpacing(0.0349f), 0f)
        assertEquals(0.04f, snapLetterSpacing(0.035f), 0f)
        assertEquals(-0.05f, snapLetterSpacing(-0.2f), 0f)
        assertEquals(0.25f, snapLetterSpacing(1f), 0f)
        assertEquals(0f, snapLetterSpacing(0.004f), 0f)
    }

    @Test
    fun `letter spacing labels`() {
        assertEquals("Normal", formatLetterSpacing(0f, "Normal"))
        assertEquals("+0.12 em", formatLetterSpacing(0.12f, "Normal"))
        assertEquals("-0.05 em", formatLetterSpacing(-0.05f, "Normal"))
    }

    @Test
    fun `font size steps by ten percent and snaps off-grid values`() {
        assertEquals(110, stepFontScale(100, +1))
        assertEquals(90, stepFontScale(100, -1))
        assertEquals(110, stepFontScale(105, +1))
        assertEquals(100, stepFontScale(105, -1))
        assertEquals(MAX_FONT_SCALE, stepFontScale(250, +1))
        assertEquals(MIN_FONT_SCALE, stepFontScale(80, -1))
    }

    @Test
    fun `line spacing and margins light up the nearest preset step`() {
        assertEquals(1.5f, nearestStep(1.55f, LineSpacingSteps), 0f)
        assertEquals(1.8f, nearestStep(2.4f, LineSpacingSteps), 0f)
        assertEquals(1.3f, nearestStep(1.0f, LineSpacingSteps), 0f)
        assertEquals(24, nearestStep(20, MarginSteps))
        assertEquals(40, nearestStep(48, MarginSteps))
    }
}
