package org.hello.reader.feature.library

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionFormatterTest {

    @Test
    fun `formatDescription strips raw markdown asterisks and applies bold span style`() {
        val raw = "This is **very important** text."
        val formatted = formatDescription(raw)
        assertEquals("This is very important text.", formatted.text)
        val boldSpan = formatted.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold }
        org.junit.Assert.assertNotNull(boldSpan)
        assertEquals(8, boldSpan!!.start)
        assertEquals(22, boldSpan.end)
    }

    @Test
    fun `formatDescription strips italic markers and applies italic span style`() {
        val raw = "An *extraordinary* and _captivating_ story."
        val formatted = formatDescription(raw)
        assertEquals("An extraordinary and captivating story.", formatted.text)
        val italicSpans = formatted.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(2, italicSpans.size)
    }

    @Test
    fun `formatDescription converts html paragraphs and breaks to newlines without tags`() {
        val raw = "<p>First paragraph.</p><p>Second paragraph with<br/>line break.</p>"
        val formatted = formatDescription(raw)
        assertTrue(formatted.text.contains("First paragraph."))
        assertTrue(formatted.text.contains("Second paragraph with\nline break."))
        org.junit.Assert.assertFalse(formatted.text.contains("<p>"))
        org.junit.Assert.assertFalse(formatted.text.contains("</p>"))
        org.junit.Assert.assertFalse(formatted.text.contains("<br/>"))
    }

    @Test
    fun `formatDescription extracts link text without raw url syntax`() {
        val raw = "Visit [Storyteller](https://storyteller.example.com) for details."
        val formatted = formatDescription(raw)
        assertEquals("Visit Storyteller for details.", formatted.text)
        org.junit.Assert.assertFalse(formatted.text.contains("https://storyteller.example.com"))
    }
}
