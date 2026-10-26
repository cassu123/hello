package org.hello.reader.core.engine

import org.hello.reader.core.engine.audio.AudioPlaybackController
import org.hello.reader.core.engine.audio.BackgroundHandoff
import org.hello.reader.core.engine.audio.PlaybackOwner
import org.hello.reader.core.engine.audio.PlaybackSession
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.engine.smil.ChapterParagraph
import org.hello.reader.core.engine.smil.SmilParNode
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.SyncCheckpoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionTest {

    private class FakeAudio : AudioPlaybackController {
        var playing = false
        var position = 0L
        var stopped = false
        var lastVolume = 1f
        var lastSpeed: Float? = null
        var mediaId: String? = null
        override var hasLoadedMedia = true
        override fun prepareFile(audioFile: File, title: String, artist: String?, artworkUri: String?, initialPositionMs: Long, playbackSpeed: Float) = Unit
        override fun prepareFile(audioFile: File, title: String, artist: String?, artworkUri: String?, initialPositionMs: Long, playbackSpeed: Float, bookId: String?) {
            if (!bookId.isNullOrBlank()) {
                mediaId = "${PlaybackSession.mediaIdPrefix(bookId)}0"
            }
        }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun setSpeed(speed: Float) { lastSpeed = speed }
        override fun setVolume(volume: Float) { lastVolume = volume }
        override fun currentPositionMs(): Long = position
        override fun currentMediaId(): String? = mediaId
        override fun isPlaybackRequested(): Boolean = playing
        override fun stop() {
            playing = false
            stopped = true
        }
        override fun release() = Unit
    }

    private class Reader : PlaybackOwner {
        var pausedBySession = false
        override fun pauseFromSession() { pausedBySession = true }
    }

    private val book = Book(id = "b1", serverId = "s1", title = "Book", authors = listOf("A"), mediaType = BookMediaType.READALOUD)
    private val chapters = BookChapters.build(
        listOf(
            ChapterParagraph("p1", "1.xhtml", "One", 0, 60_000),
            ChapterParagraph("p2", "2.xhtml", "Two", 60_000, 120_000),
        ),
        timed = true,
    )
    private val handoff = BackgroundHandoff(
        book = book,
        checkpoint = SyncCheckpoint(
            bookId = "b1", serverId = "s1", userId = "u1", readingCfi = null, chapterIndex = 0, chapterTitle = "One",
            audioPositionMs = 10_000, totalDurationMs = 120_000, progressPercent = 8f, activeSentenceId = "p1",
        ),
        smilNodes = listOf(
            SmilParNode("a", "1.xhtml", "p1", "audio.mp3", 0, 60_000),
            SmilParNode("b", "2.xhtml", "p2", "audio.mp3", 60_000, 120_000),
        ),
        chapters = chapters,
        speed = 1.25f,
    )

    private fun sessionTest(body: suspend TestScope.(PlaybackSession, FakeAudio, MutableList<SyncCheckpoint>) -> Unit) = runTest {
        val audio = FakeAudio()
        val saved = mutableListOf<SyncCheckpoint>()
        val scope = TestScope(testScheduler)
        val session = PlaybackSession({ audio }, { saved += it }, scope, clock = { testScheduler.currentTime })
        try {
            body(session, audio, saved)
        } finally {
            scope.cancel()
        }
    }

    /** A reader opens the book, loads its audio, starts playing and closes. */
    private fun PlaybackSession.playThenClose(audio: FakeAudio, reader: Reader = Reader()) {
        attach("b1", reader)
        markLoaded(reader, "b1")
        audio.play()
        audio.position = 10_000
        detach(reader, handoff)
    }

    @Test
    fun `closing the reader mid-playback keeps playing and saves the position as it goes`() = sessionTest { session, audio, saved ->
        session.playThenClose(audio)
        assertEquals(10_000L, session.nowPlaying.value?.positionMs)
        assertEquals(1.25f, session.nowPlaying.value?.speed)

        audio.position = 70_000
        advanceTimeBy(PlaybackSession.SAVE_INTERVAL_MS + PlaybackSession.TICK_MS)
        runCurrent()

        assertEquals("Two", session.nowPlaying.value?.chapterTitle)
        val last = saved.last()
        assertEquals(70_000L, last.audioPositionMs)
        assertEquals(1, last.chapterIndex)
        assertEquals("p2", last.activeSentenceId)
        assertTrue(last.isDirty)
    }

    @Test
    fun `the mini player pauses, resumes, skips and closes`() = sessionTest { session, audio, saved ->
        session.playThenClose(audio)

        session.togglePlayPause()
        assertFalse(audio.playing)
        assertFalse(session.nowPlaying.value!!.isPlaying)
        runCurrent()
        assertEquals(10_000L, saved.last().audioPositionMs) // pausing saves

        session.skipBy(30_000)
        assertEquals(40_000L, audio.position)

        session.togglePlayPause()
        assertTrue(audio.playing)

        session.stop()
        assertTrue(audio.stopped)
        assertNull(session.nowPlaying.value)
    }

    @Test
    fun `pausing from the notification shows up in the mini player`() = sessionTest { session, audio, _ ->
        session.playThenClose(audio)
        audio.playing = false
        advanceTimeBy(PlaybackSession.TICK_MS + 1)
        assertFalse(session.nowPlaying.value!!.isPlaying)
    }

    @Test
    fun `reopening the same book attaches to the live audio, another book stops it`() = sessionTest { session, audio, saved ->
        session.playThenClose(audio)
        audio.position = 15_000

        val live = session.attach("b1", Reader())
        assertNotNull(live)
        assertEquals(15_000L, live?.positionMs)
        assertEquals(true, live?.isPlaying)
        assertFalse(audio.stopped)

        session.detach(Reader(), handoff) // a stale reader closing late is ignored
        assertFalse(audio.stopped)

        val other = session.attach("b2", Reader())
        assertNull(other)
        assertTrue(audio.stopped)
        assertNull(session.nowPlaying.value)
        assertTrue(saved.isEmpty() || saved.last().bookId == "b1")
    }

    @Test
    fun `startFromLibrary prepares controller, seeks, plays and starts background tracking`() = sessionTest { session, audio, saved ->
        val trackFile = File("track1.mp3")
        val tracks = listOf(
            org.hello.reader.core.engine.smil.AudioTrackItem(trackFile, "track1.mp3", 0L, 120_000L),
        )

        session.startFromLibrary(handoff, tracks, 45_000L, artworkUri = "content://covers/b1.jpg")

        assertTrue(audio.playing)
        assertEquals(45_000L, audio.position)
        assertEquals(45_000L, session.nowPlaying.value?.positionMs)
        assertEquals("Book", session.nowPlaying.value?.book?.title)

        // Ticker advances and persists
        audio.position = 50_000L
        advanceTimeBy(PlaybackSession.SAVE_INTERVAL_MS + PlaybackSession.TICK_MS)
        runCurrent()

        assertTrue(saved.isNotEmpty())
        assertEquals(50_000L, saved.last().audioPositionMs)
    }

    @Test
    fun `adopting another book saves the replaced background book and applies the new speed`() = sessionTest { session, audio, saved ->
        session.playThenClose(audio)
        audio.position = 20_000
        advanceTimeBy(PlaybackSession.TICK_MS * 2)
        runCurrent()
        saved.clear() // well inside SAVE_INTERVAL_MS, so b1 at 20s hasn't been saved yet

        val bookB = book.copy(id = "b2", title = "Other")
        val handoffB = handoff.copy(
            book = bookB,
            checkpoint = handoff.checkpoint.copy(bookId = "b2", audioPositionMs = 0),
            speed = 1.75f,
        )
        val tracks = listOf(org.hello.reader.core.engine.smil.AudioTrackItem(File("b2.mp3"), "b2.mp3", 0L, 120_000L))
        session.adoptBackgroundPlayback(handoffB, tracks, startMs = 30_000L)
        runCurrent()

        val savedA = saved.single { it.bookId == "b1" }
        assertEquals(20_000L, savedA.audioPositionMs)
        assertEquals(1.75f, audio.lastSpeed)
        assertEquals("b2", session.nowPlaying.value?.book?.id)
    }

    private val bookB = book.copy(id = "b2", title = "Other")
    private val handoffB = handoff.copy(
        book = bookB,
        checkpoint = handoff.checkpoint.copy(bookId = "b2", audioPositionMs = 0),
    )
    private val tracksB = listOf(org.hello.reader.core.engine.smil.AudioTrackItem(File("b2.mp3"), "b2.mp3", 0L, 120_000L))

    /** Book A plays in the background, then is paused there (e.g. from the notification). */
    private suspend fun TestScope.pauseBookAInBackground(session: PlaybackSession, audio: FakeAudio) {
        audio.mediaId = PlaybackSession.mediaIdPrefix("b1") + "0"
        session.playThenClose(audio)
        audio.position = 90_000
        audio.pause()
        advanceTimeBy(PlaybackSession.TICK_MS)
        runCurrent()
    }

    @Test
    fun `adopting a book never saves it at the old book's position while the player switches`() = sessionTest { session, audio, saved ->
        pauseBookAInBackground(session, audio)
        saved.clear()

        // Media3 hasn't swapped the items in yet: the player is still on book A, paused at 90s.
        session.adoptBackgroundPlayback(handoffB, tracksB, startMs = 30_000L)
        advanceTimeBy(PlaybackSession.TICK_MS * 4)
        runCurrent()

        assertTrue(saved.none { it.bookId == "b2" })
        assertEquals("b2", session.nowPlaying.value?.book?.id)
        assertEquals(30_000L, session.nowPlaying.value?.positionMs)
    }

    @Test
    fun `once the player is on the adopted book its position is followed and saved`() = sessionTest { session, audio, saved ->
        pauseBookAInBackground(session, audio)
        session.adoptBackgroundPlayback(handoffB, tracksB, startMs = 30_000L)
        runCurrent()

        audio.mediaId = PlaybackSession.mediaIdPrefix("b2") + "0"
        audio.position = 31_000
        audio.play()
        advanceTimeBy(PlaybackSession.TICK_MS)
        runCurrent()
        assertEquals(31_000L, session.nowPlaying.value?.positionMs)

        audio.pause()
        advanceTimeBy(PlaybackSession.TICK_MS)
        runCurrent()
        assertEquals(31_000L, saved.last { it.bookId == "b2" }.audioPositionMs)
    }

    @Test
    fun `a player that never reports the adopted book is followed after the timeout`() = sessionTest { session, audio, _ ->
        session.adoptBackgroundPlayback(handoffB, tracksB, startMs = 30_000L)
        audio.position = 45_000
        advanceTimeBy(PlaybackSession.MEDIA_SWITCH_TIMEOUT_MS + PlaybackSession.TICK_MS)
        runCurrent()
        assertEquals(45_000L, session.nowPlaying.value?.positionMs)
    }

    @Test
    fun `the sleep timer keeps running after the reader closes`() = sessionTest { session, audio, _ ->
        session.playThenClose(audio)
        session.startSleepTimer(minutes = 1)
        advanceTimeBy(61_000)
        runCurrent()
        assertFalse(audio.playing)
        assertEquals(1f, audio.lastVolume) // volume restored after the fade
    }

    @Test
    fun `end-of-chapter sleep asks the open reader to pause`() = sessionTest { session, _, _ ->
        val reader = Reader()
        session.attach("b1", reader)
        session.sleepAt(60_000)
        session.publish(reader, org.hello.reader.core.engine.audio.NowPlaying(book, "One", 59_000, 120_000, true, 1f))
        assertFalse(reader.pausedBySession)
        session.publish(reader, org.hello.reader.core.engine.audio.NowPlaying(book, "One", 60_100, 120_000, true, 1f))
        assertTrue(reader.pausedBySession)
        assertNull(session.sleepAtMs.value)
    }

    @Test
    fun `persistAndStop saves the background position and stops playback`() = sessionTest { session, audio, saved ->
        session.playThenClose(audio)
        audio.position = 75_000
        session.persistAndStop()
        assertTrue(audio.stopped)
        assertNull(session.nowPlaying.value)
        val last = saved.last()
        assertEquals(75_000L, last.audioPositionMs)
        assertEquals(1, last.chapterIndex)
        assertEquals("p2", last.activeSentenceId)
    }

    @Test
    fun `persistAndStop saves position using lastHandoffTemplate when playback was paused before app close`() = sessionTest { session, audio, saved ->
        val reader = Reader()
        session.attach("b1", reader)
        session.markLoaded(reader, "b1")
        audio.playing = false
        audio.position = 85_000
        session.detach(reader, handoff)
        session.persistAndStop()
        assertTrue(audio.stopped)
        val last = saved.last()
        assertEquals(85_000L, last.audioPositionMs)
        assertEquals(1, last.chapterIndex)
        assertEquals("p2", last.activeSentenceId)
    }

    @Test
    fun `reader-started session with track media IDs is not treated as a switched background book`() = sessionTest { session, audio, saved ->
        val reader = Reader()
        session.attach("b1", reader)
        val tracks = listOf(org.hello.reader.core.engine.smil.AudioTrackItem(File("b1.mp3"), "b1.mp3", 0L, 120_000L))
        audio.prepareTracks(tracks, book.title, "A", null, 10_000L, 1.25f, bookId = "b1")
        assertEquals("${PlaybackSession.mediaIdPrefix("b1")}0", audio.mediaId)
        session.markLoaded(reader, "b1")
        audio.play()
        audio.position = 25_000L

        // Reader closes mid-playback; session enters background without awaiting media switch
        session.detach(reader, handoff)
        assertEquals(25_000L, session.nowPlaying.value?.positionMs)

        // Position changes and pause immediately persist without waiting for MEDIA_SWITCH_TIMEOUT_MS
        audio.position = 72_000L
        audio.pause()
        advanceTimeBy(PlaybackSession.TICK_MS)
        runCurrent()

        assertEquals(72_000L, session.nowPlaying.value?.positionMs)
        assertEquals(72_000L, saved.last { it.bookId == "b1" }.audioPositionMs)
    }

    @Test
    fun `snapshotAndStop captures position before player release when service is destroyed while paused`() = sessionTest { session, audio, saved ->
        session.playThenClose(audio)
        audio.position = 88_000L
        audio.pause()
        advanceTimeBy(PlaybackSession.TICK_MS)
        runCurrent()
        saved.clear()

        // Advance position while paused (e.g. seek before service destroy), snapshot before player release
        audio.position = 95_000L
        val snapshot = session.snapshotAndStop()
        assertNotNull(snapshot)
        assertEquals(95_000L, snapshot!!.audioPositionMs)
        assertEquals(1, snapshot.chapterIndex)
        assertEquals("p2", snapshot.activeSentenceId)

        // Simulate player.release() zeroing position after snapshot, then persist on application scope
        audio.position = 0L
        session.persistCheckpoint(snapshot)
        assertEquals(95_000L, saved.single().audioPositionMs)
    }

    @Test
    fun `detach while paused keeps nowPlaying loaded and active for mini player`() = sessionTest { session, audio, saved ->
        val reader = Reader()
        session.attach("b1", reader)
        session.markLoaded(reader, "b1")
        audio.playing = false
        audio.position = 45_000L
        session.detach(reader, handoff)

        val playing = session.nowPlaying.value
        assertNotNull(playing)
        assertEquals("b1", playing?.book?.id)
        assertFalse(playing!!.isPlaying)
        assertEquals(45_000L, playing.positionMs)

        // togglePlayPause starts playback
        session.togglePlayPause()
        assertTrue(audio.playing)
        advanceTimeBy(PlaybackSession.TICK_MS)
        runCurrent()
        assertTrue(session.nowPlaying.value!!.isPlaying)

        // skipBy moves position
        session.skipBy(30_000L)
        assertEquals(75_000L, audio.position)

        // stop clears nowPlaying and stops controller
        session.stop()
        assertNull(session.nowPlaying.value)
        assertTrue(audio.stopped)
    }
}
