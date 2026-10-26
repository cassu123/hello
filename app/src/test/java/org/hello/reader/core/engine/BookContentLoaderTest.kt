package org.hello.reader.core.engine

import java.io.File
import org.hello.reader.core.engine.smil.AudioTrackItem
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.engine.smil.ChapterParagraph
import org.hello.reader.core.engine.smil.SmilParNode
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.feature.reader.ReaderSentenceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookContentLoaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleSentences = listOf(
        ReaderSentenceItem(
            elementId = "sent_1",
            textHref = "chapter1.xhtml",
            text = "First sentence.",
            clipBeginMs = 0L,
            clipEndMs = 5_000L,
            rawHtmlId = "h1",
        ),
        ReaderSentenceItem(
            elementId = "sent_2",
            textHref = "chapter1.xhtml",
            text = "Second sentence.",
            clipBeginMs = 5_000L,
            clipEndMs = 12_500L,
            rawHtmlId = "h2",
        ),
        ReaderSentenceItem(
            elementId = "sent_3",
            textHref = "chapter2.xhtml",
            text = "Third sentence.",
            clipBeginMs = 12_500L,
            clipEndMs = 25_000L,
            rawHtmlId = "h3",
        ),
    )

    private val sampleSmilNodes = listOf(
        SmilParNode("par_1", "chapter1.xhtml", "h1", "audio1.mp3", 0L, 5_000L),
        SmilParNode("par_2", "chapter1.xhtml", "h2", "audio1.mp3", 5_000L, 12_500L),
        SmilParNode("par_3", "chapter2.xhtml", "h3", "audio2.mp3", 12_500L, 25_000L),
    )

    private val sampleChapters = BookChapters.build(
        paragraphs = sampleSentences.map {
            ChapterParagraph(it.elementId, it.textHref, it.text, it.clipBeginMs, it.clipEndMs)
        },
        timed = true,
    )

    private val sampleTracks = listOf(
        AudioTrackItem(File("audio1.mp3"), "audio1.mp3", 0L, 12_500L),
        AudioTrackItem(File("audio2.mp3"), "audio2.mp3", 12_500L, 12_500L),
    )

    private val loadedContent = LoadedBookContent(
        smilNodes = sampleSmilNodes,
        sentences = sampleSentences,
        audioFile = File("audio1.mp3"),
        audioTracks = sampleTracks,
        tocTitles = mapOf("chapter1.xhtml" to "Chapter 1", "chapter2.xhtml" to "Chapter 2"),
        chapters = sampleChapters,
    )

    @Test
    fun `remote checkpoint with audioPositionMs 0 and activeSentenceId X starts at X clipBegin`() {
        val remoteCheckpoint = SyncCheckpoint(
            bookId = "book_1",
            serverId = "server_1",
            userId = "user_1",
            readingCfi = "chapter1.xhtml",
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            audioPositionMs = 0L, // No audio clock in Storyteller v2 Readium checkpoint
            totalDurationMs = 25_000L,
            progressPercent = 20f,
            activeSentenceId = "h2", // Refers to second sentence
            updatedAt = System.currentTimeMillis(),
            isDirty = false,
        )

        val resolvedMs = BookContentLoader.resolveCheckpointAudioPosition(remoteCheckpoint, loadedContent)

        assertEquals(5_000L, resolvedMs)
    }

    @Test
    fun `checkpoint with positive audioPositionMs preserves exact audio clock`() {
        val checkpoint = SyncCheckpoint(
            bookId = "book_1",
            serverId = "server_1",
            userId = "user_1",
            readingCfi = "chapter2.xhtml",
            chapterIndex = 1,
            chapterTitle = "Chapter 2",
            audioPositionMs = 18_450L,
            totalDurationMs = 25_000L,
            progressPercent = 73f,
            activeSentenceId = "h3",
        )

        val resolvedMs = BookContentLoader.resolveCheckpointAudioPosition(checkpoint, loadedContent)

        assertEquals(18_450L, resolvedMs)
    }

    @Test
    fun `checkpoint with t= format resolves track offset and seconds`() {
        val checkpoint = SyncCheckpoint(
            bookId = "book_1",
            serverId = "server_1",
            userId = "user_1",
            readingCfi = "audio2.mp3",
            chapterIndex = 1,
            chapterTitle = "Chapter 2",
            audioPositionMs = 0L,
            totalDurationMs = 25_000L,
            progressPercent = 50f,
            activeSentenceId = "t=4.5", // 4.5 seconds into audio2.mp3 (which starts at 12_500)
        )

        val resolvedMs = BookContentLoader.resolveCheckpointAudioPosition(checkpoint, loadedContent)

        assertEquals(12_500L + 4_500L, resolvedMs)
    }

    @Test
    fun `resolveAudioTracks orders files using NaturalFileOrder`() {
        val dir = tempFolder.newFolder("audio_tracks")
        val file10 = File(dir, "track10.mp3").apply { writeText("dummy") }
        val file2 = File(dir, "track2.mp3").apply { writeText("dummy") }
        val file1 = File(dir, "track1.mp3").apply { writeText("dummy") }
        val nonAudio = File(dir, "cover.jpg").apply { writeText("dummy") }

        val tracks = BookContentLoader.resolveAudioTracks(dir.absolutePath)

        assertEquals(3, tracks.size)
        assertEquals("track1.mp3", tracks[0].name)
        assertEquals("track2.mp3", tracks[1].name)
        assertEquals("track10.mp3", tracks[2].name)
        assertTrue(tracks.none { it.name.endsWith(".jpg") })
    }
}
