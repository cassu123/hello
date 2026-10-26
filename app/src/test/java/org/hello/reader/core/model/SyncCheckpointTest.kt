package org.hello.reader.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCheckpointTest {

    private fun checkpoint(
        audioMs: Long = 0L,
        durationMs: Long = 0L,
        percent: Float = 1f,
        href: String? = "chapter01.xhtml",
        sentence: String? = null,
    ) = SyncCheckpoint(
        bookId = "b1",
        serverId = "s1",
        userId = "u1",
        readingCfi = href,
        chapterIndex = 0,
        chapterTitle = null,
        audioPositionMs = audioMs,
        totalDurationMs = durationMs,
        progressPercent = percent,
        activeSentenceId = sentence,
    )

    @Test
    fun `a listening position does not conflict with a server locator at the same progress`() {
        // The reported loop: this device at 14:30 (1%), the server's Readium locator at 1% with no audio clock.
        val local = checkpoint(audioMs = 870_000L, durationMs = 46_000_000L, percent = 1f)
        val remote = checkpoint(percent = 1f, sentence = "s-12")
        assertFalse(local.conflictsWith(remote))
    }

    @Test
    fun `a server locator far ahead in the book still conflicts`() {
        val local = checkpoint(audioMs = 870_000L, durationMs = 46_000_000L, percent = 1f)
        assertTrue(local.conflictsWith(checkpoint(percent = 30f)))
    }

    @Test
    fun `the same sentence never conflicts whatever the audio clocks say`() {
        val local = checkpoint(audioMs = 10_000L, durationMs = 600_000L, sentence = "s-4")
        val remote = checkpoint(audioMs = 500_000L, durationMs = 600_000L, sentence = "s-4")
        assertFalse(local.conflictsWith(remote))
    }

    @Test
    fun `audio clocks on both sides more than a minute apart conflict`() {
        val local = checkpoint(audioMs = 10_000L, durationMs = 600_000L)
        assertTrue(local.conflictsWith(checkpoint(audioMs = 120_000L, durationMs = 600_000L)))
        assertFalse(local.conflictsWith(checkpoint(audioMs = 60_000L, durationMs = 600_000L)))
    }
}
