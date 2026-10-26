package org.hello.reader.feature.library

import org.hello.reader.core.model.SyncCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFormattersTest {

    @Test
    fun `remaining time labels`() {
        assertEquals(2L to 5L, LibraryFormatters.hoursAndMinutes(2 * 3_600_000L + 5 * 60_000L + 59_000L))
        assertEquals(0L to 12L, LibraryFormatters.hoursAndMinutes(12 * 60_000L))
        assertEquals(0L to 0L, LibraryFormatters.hoursAndMinutes(59_000L))
    }

    @Test
    fun `position labels`() {
        assertEquals("1:02:03", LibraryFormatters.position(3_723_000L))
        assertEquals("4:05", LibraryFormatters.position(245_400L))
        assertEquals("0:00", LibraryFormatters.position(-5L))
    }

    @Test
    fun `percent labels clamp`() {
        assertEquals("42%", LibraryFormatters.percent(0.425f))
        assertEquals("100%", LibraryFormatters.percent(1.7f))
    }

    @Test
    fun `formatBytes representations`() {
        assertEquals("0 B", LibraryFormatters.formatBytes(0L))
        assertEquals("500 B", LibraryFormatters.formatBytes(500L))
        assertEquals("1.5 KB", LibraryFormatters.formatBytes(1536L))
        assertEquals("124.0 MB", LibraryFormatters.formatBytes(124L * 1024L * 1024L))
        assertEquals("1.5 GB", LibraryFormatters.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `conflict description includes chapter, position and percent`() {
        val audio = SyncCheckpoint(
            bookId = "b", serverId = "s", userId = "u", readingCfi = null, chapterIndex = 2,
            chapterTitle = null, audioPositionMs = 3_723_000L, totalDurationMs = 10_000_000L,
            progressPercent = 37.2f, activeSentenceId = null, updatedAt = 0L,
        )
        assertEquals("Chapter 3 · 1:02:03 · 37%", describe(audio, "Chapter 3"))
        assertEquals(
            "Prologue · 5%",
            describe(audio.copy(chapterTitle = "Prologue", audioPositionMs = 0L, totalDurationMs = 0L, progressPercent = 5f), "Prologue"),
        )
    }
}
