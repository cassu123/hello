package org.hello.reader.feature.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import org.hello.reader.core.data.AnnotationRepository
import org.hello.reader.core.data.BookRepository
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.database.entity.BookDownloadEntity
import org.hello.reader.core.database.entity.SyncCheckpointEntity
import org.hello.reader.core.engine.audio.AudioPlaybackController
import org.hello.reader.core.engine.audio.PlaybackSession
import org.hello.reader.core.engine.reader.ReaderMode
import org.hello.reader.core.engine.reader.SyncedPlaybackState
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.core.model.LocalLibrary
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.network.DictionaryEntry
import org.hello.reader.core.network.DictionaryResult
import org.hello.reader.core.network.NetworkModule
import org.hello.reader.core.network.ServerAuthInterceptor
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.testing.FakeAnnotationDao
import org.hello.reader.testing.FakeBookDao
import org.hello.reader.testing.FakeBookDownloadDao
import org.hello.reader.testing.FakeSyncCheckpointDao
import org.hello.reader.testing.InMemorySharedPreferences
import org.hello.reader.testing.profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var vault: SecurityVaultImpl
    private lateinit var bookDao: FakeBookDao
    private lateinit var checkpointDao: FakeSyncCheckpointDao
    private lateinit var downloadDao: FakeBookDownloadDao
    private lateinit var repository: BookRepository
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var preferences: ReaderPreferencesRepository
    private val store = ViewModelStore()
    private lateinit var annotations: AnnotationRepository
    /** Replace per test to control dictionary results. */
    private var lookup: suspend (String) -> DictionaryResult = { DictionaryResult.NotFound(it) }
    private val audio = FakeAudioController()
    private var persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var sessionScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private lateinit var playback: PlaybackSession

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        sessionScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        server = MockWebServer().apply {
            // No remote checkpoint and no sync endpoint: the reader must work offline.
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
            }
            start()
        }
        vault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        runBlocking {
            vault.saveProfile(profile("s1", baseUrl = server.url("/").toString().trimEnd('/'), userId = "u1", isActive = true))
        }
        bookDao = FakeBookDao()
        checkpointDao = FakeSyncCheckpointDao()
        downloadDao = FakeBookDownloadDao()
        val client = NetworkModule.okHttpClient(ServerAuthInterceptor({ vault.activeProfile.value }) {}, enableLogging = false)
        repository = BookRepository(
            api = NetworkModule.storytellerApi(client),
            bookDao = bookDao,
            checkpointDao = checkpointDao,
            vault = vault,
            downloadDao = downloadDao,
            clock = { 1_000L },
        )
        annotations = AnnotationRepository(FakeAnnotationDao(), vault)
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        preferences = ReaderPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) { tmp.newFile("reader.preferences_pb").also { it.delete() } },
        )
        playback = PlaybackSession({ audio }, repository::saveCheckpoint, sessionScope, clock = { 1_000L })
    }

    @After
    fun tearDown() {
        store.clear()
        sessionScope.cancel()
        dataStoreScope.cancel()
        persistenceScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun seedBook(
        id: String = "b1",
        type: BookMediaType = BookMediaType.READALOUD,
        serverId: String = "s1",
        userId: String = "u1",
        audioPath: String? = null,
        filePath: String? = twoChapterEpub(),
        downloadStatus: DownloadStatus = DownloadStatus.DOWNLOADED,
        hasAudiobook: Boolean = type == BookMediaType.AUDIO_ONLY,
        hasEbook: Boolean = type != BookMediaType.AUDIO_ONLY,
        hasReadaloud: Boolean = type == BookMediaType.READALOUD,
        readingStatus: String? = null,
        readingStatusLabel: String? = null,
    ) = runBlocking {
        bookDao.upsertBooks(
            listOf(
                BookEntity(
                    id = id, serverId = serverId, userId = userId, title = "Synced Book", authors = "[\"River Song\"]",
                    narrators = "[]", coverUrl = null, description = null, mediaType = type, durationSeconds = 600L,
                    totalChapters = 1, downloadStatus = downloadStatus, downloadProgress = if (downloadStatus == DownloadStatus.DOWNLOADED) 1f else 0f,
                    localFilePath = filePath, tags = "[]", createdAt = 0L, lastOpenedAt = null, localAudioPath = audioPath,
                    hasAudiobook = hasAudiobook, hasEbook = hasEbook, hasReadaloud = hasReadaloud,
                    readingStatus = readingStatus, readingStatusLabel = readingStatusLabel,
                ),
            ),
        )
    }

    /** Created through a real ViewModelStore so `store.clear()` runs the genuine onCleared path. */
    private fun viewModel(bookId: String = "b1"): ReaderViewModel =
        ViewModelProvider.create(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    ReaderViewModel(
                        bookId = bookId,
                        bookRepository = repository,
                        preferencesRepository = preferences,
                        vault = vault,
                        annotationRepository = annotations,
                        lookupWord = { lookup(it) },
                        playback = playback,
                        persistenceScope = persistenceScope,
                    ) as T
            },
        )[ReaderViewModel::class.java]

    /**
     * runTest drains virtual time when the body returns; the reader's playback
     * ticker would keep rescheduling itself, so the ViewModel is cleared first.
     */
    private fun readerTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            store.clear()
            // Background playback (after the reader closes) also ticks forever.
            sessionScope.cancel()
        }
    }

    /** A readaloud EPUB with two chapters of two sentences each: 0-20s and 20-40s, titled by its nav. */
    private fun twoChapterEpub(): String {
        val file = tmp.newFile("two-chapters.epub")
        fun smil(chapter: Int, offsetSeconds: Int) = """
            <smil><body>
              <par><text src="../Text/ch$chapter.xhtml#c${chapter}s1"/><audio src="../Audio/book.mp3" clipBegin="${offsetSeconds}s" clipEnd="${offsetSeconds + 10}s"/></par>
              <par><text src="../Text/ch$chapter.xhtml#c${chapter}s2"/><audio src="../Audio/book.mp3" clipBegin="${offsetSeconds + 10}s" clipEnd="${offsetSeconds + 20}s"/></par>
            </body></smil>
        """
        fun xhtml(chapter: Int) =
            """<html><body><p id="c${chapter}s1">Chapter $chapter opens.</p><p id="c${chapter}s2">Chapter $chapter closes.</p></body></html>"""
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("OEBPS/nav.xhtml", """<nav epub:type="toc"><a href="Text/ch1.xhtml">Arrival</a><a href="Text/ch2.xhtml">Departure</a></nav>""")
            put("OEBPS/Smil/ch1.smil", smil(1, 0))
            put("OEBPS/Smil/ch2.smil", smil(2, 20))
            put("OEBPS/Text/ch1.xhtml", xhtml(1))
            put("OEBPS/Text/ch2.xhtml", xhtml(2))
            put("OEBPS/Audio/book.mp3", "ID3")
        }
        return file.path
    }

    @Test
    fun `chapters come from the book's contents and drive navigation, time left and the sleep timer`() = readerTest {
        seedBook(filePath = twoChapterEpub())
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        val chapters = vm.chapters.value
        assertEquals(listOf("Arrival", "Departure"), chapters.chapters.map { it.title })
        assertTrue(chapters.hasTiming)

        vm.nextChapter()
        var mode = vm.readerMode.value as ReaderMode.ReadaloudSynced
        assertEquals(20_000L, audio.lastSeekMs)
        assertEquals(1, mode.chapterIndex)
        assertEquals("Departure", mode.chapterTitle)
        assertEquals(20_000L, chapterRemainingMs(chapters, mode))

        vm.seekToMs(31_000L)
        vm.previousChapter() // 11s into the chapter: back to its start
        assertEquals(20_000L, audio.lastSeekMs)
        vm.previousChapter() // at the start: the chapter before
        assertEquals(0L, audio.lastSeekMs)
        mode = vm.readerMode.value as ReaderMode.ReadaloudSynced
        assertEquals("Arrival", mode.chapterTitle)

        vm.sleepAtEndOfChapter()
        assertEquals(20_000L, playback.sleepAtMs.value)

        // Reading silently, a chapter jump scrolls the text instead.
        vm.toggleSynchronizedNarration()
        val scrolled = backgroundScope.async(kotlinx.coroutines.Dispatchers.Unconfined) { vm.scrollRequests.first() }
        vm.goToChapter(1)
        assertEquals("c2s1", scrolled.await().elementId)
    }

    @Test
    fun `a chapter jump during narration turns to the chapter even when the reader paged away`() = readerTest {
        seedBook(filePath = twoChapterEpub())
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        val scrolled = backgroundScope.async(kotlinx.coroutines.Dispatchers.Unconfined) { vm.scrollRequests.first() }
        vm.goToChapter(1)
        val target = scrolled.await()
        assertEquals("c2s1", target.elementId)
        assertFalse(target.pauseAutoFollow)
        assertEquals(20_000L, audio.lastSeekMs)
    }

    @Test
    fun `paging or visible element changes while narration is paused keeps narration position, highlight, and checkpoint`() = readerTest {
        seedBook(filePath = twoChapterEpub())
        val vm = viewModel()
        val opened = vm.awaitMode { it is ReaderMode.ReadaloudSynced } as ReaderMode.ReadaloudSynced
        assertNotEquals(SyncedPlaybackState.PLAYING, opened.playbackState)
        val initialAudioMs = opened.audioPositionMs
        val initialHighlight = opened.highlight?.elementId
        val initialChapter = opened.chapterTitle

        // Page changed / visible element reported for chapter 2 sentence 1
        vm.onVisibleElementChanged("c2s1")

        val mode = vm.readerMode.value as ReaderMode.ReadaloudSynced
        assertEquals(initialAudioMs, mode.audioPositionMs)
        assertEquals(initialHighlight, mode.highlight?.elementId)
        assertEquals(initialChapter, mode.chapterTitle)
        assertEquals(opened.progressPercent, mode.progressPercent, 0.01f)

        // Advance debounce time; checkpoint must remain on narration position
        mainDispatcher.scheduler.advanceTimeBy(2_100L)
        mainDispatcher.scheduler.runCurrent()
        val checkpoint = checkpointDao.observeCheckpoint("b1", "s1", "u1").first()
        assertEquals(initialAudioMs, checkpoint?.audioPositionMs ?: 0L)
        assertEquals(initialHighlight, checkpoint?.activeSentenceId ?: initialHighlight)

        // Resuming playback plays from the paused narration position
        vm.play()
        val playingMode = vm.readerMode.value as ReaderMode.ReadaloudSynced
        assertEquals(SyncedPlaybackState.PLAYING, playingMode.playbackState)
        assertEquals(initialAudioMs, playingMode.audioPositionMs)
        assertEquals(initialHighlight, playingMode.highlight?.elementId)
    }

    @Test
    fun `scrolling while reading silently moves the saved position and chapter`() = readerTest {
        seedBook(type = BookMediaType.EBOOK_ONLY, filePath = twoChapterEpub())
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadingOnly }

        vm.onVisibleElementChanged("c2s2")
        val mode = vm.readerMode.value as ReaderMode.ReadingOnly
        assertEquals("c2s2", mode.activeSentenceId)
        assertEquals(100f, mode.progressPercent)
        assertEquals("Departure", mode.chapterTitle)
        assertNull(vm.readingAnchor.value) // the text isn't asked to scroll to where it already is

        mainDispatcher.scheduler.advanceTimeBy(2_100L)
        mainDispatcher.scheduler.runCurrent()
        val saved = eventually { checkpointDao.observeCheckpoint("b1", "s1", "u1").first { it?.activeSentenceId == "c2s2" } }
        assertEquals(1, saved!!.chapterIndex)
    }

    @Test
    fun `reopening an e-book offline restores the exact saved sentence`() = readerTest {
        seedBook(type = BookMediaType.EBOOK_ONLY, filePath = twoChapterEpub())
        val vm1 = viewModel()
        vm1.awaitMode { it is ReaderMode.ReadingOnly }

        vm1.onVisibleElementChanged("c2s2")
        mainDispatcher.scheduler.advanceTimeBy(2_100L)
        mainDispatcher.scheduler.runCurrent()
        eventually { checkpointDao.observeCheckpoint("b1", "s1", "u1").first { it?.activeSentenceId == "c2s2" } }

        store.clear()

        val vm2 = viewModel()
        vm2.awaitMode { it is ReaderMode.ReadingOnly }
        val mode = vm2.readerMode.value as ReaderMode.ReadingOnly
        assertEquals("c2s2", mode.activeSentenceId)
        assertEquals("Departure", mode.chapterTitle)
        assertEquals(1, mode.chapterIndex)
    }

    private fun twoDiscAudioEpub(): String {
        val file = tmp.newFile("two-disc.epub")
        fun smil(chapter: Int, disc: Int, offsetSeconds: Int) = """
            <smil><body>
              <par><text src="../Text/ch$chapter.xhtml#c${chapter}s1"/><audio src="../Audio/disc$disc/01.mp3" clipBegin="0s" clipEnd="10s"/></par>
              <par><text src="../Text/ch$chapter.xhtml#c${chapter}s2"/><audio src="../Audio/disc$disc/01.mp3" clipBegin="10s" clipEnd="20s"/></par>
            </body></smil>
        """
        fun xhtml(chapter: Int) =
            """<html><body><p id="c${chapter}s1">Chapter $chapter opens.</p><p id="c${chapter}s2">Chapter $chapter closes.</p></body></html>"""
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("OEBPS/nav.xhtml", """<nav epub:type="toc"><a href="Text/ch1.xhtml">Disc 1</a><a href="Text/ch2.xhtml">Disc 2</a></nav>""")
            put("OEBPS/Smil/ch1.smil", smil(1, 1, 0))
            put("OEBPS/Smil/ch2.smil", smil(2, 2, 20))
            put("OEBPS/Text/ch1.xhtml", xhtml(1))
            put("OEBPS/Text/ch2.xhtml", xhtml(2))
            put("OEBPS/Audio/disc1/01.mp3", "ID3")
            put("OEBPS/Audio/disc2/01.mp3", "ID3")
        }
        return file.path
    }

    @Test
    fun `reopening audiobook with multiple disc folders matches exact disc and not earlier disc with same filename`() = readerTest {
        seedBook(id = "b1", type = BookMediaType.READALOUD, filePath = twoDiscAudioEpub())
        checkpointDao.upsertCheckpoint(
            SyncCheckpointEntity(
                bookId = "b1",
                serverId = "s1",
                userId = "u1",
                readingCfi = "Audio/disc2/01.mp3",
                chapterIndex = 1,
                chapterTitle = "Disc 2",
                audioPositionMs = 0L,
                totalDurationMs = 40_000L,
                progressPercent = 62.5f,
                activeSentenceId = "t=5.0",
                updatedAt = 1000L,
                isDirty = false,
            )
        )
        val vm = viewModel()
        val mode = vm.awaitMode { it is ReaderMode.ReadaloudSynced } as ReaderMode.ReadaloudSynced
        assertEquals(25_000L, mode.audioPositionMs)
    }

    /** A downloaded audio file, so the reader loads it and the session knows the book is playable. */
    private fun audioFile(): String = tmp.newFile("narration.mp3").also { it.writeBytes(byteArrayOf(0x49, 0x44, 0x33)) }.path

    /** DataStore and MockWebServer run on real threads, so wait in real time, not runTest's virtual time. */
    private suspend fun <T> eventually(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(15_000) { block() } }

    /**
     * Waits for the whole open sequence (load, saved speed, server sync) to finish, not just the
     * first mode emission: otherwise the test drives the VM while the session is still writing state.
     */
    private suspend fun ReaderViewModel.awaitMode(predicate: (ReaderMode) -> Boolean): ReaderMode =
        eventually {
            sessionLoaded.first { it }
            readerMode.first(predicate)
        }

    @Test
    fun `tap-to-speak from reading mode starts synced narration at the sentence and saves a checkpoint`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced } // readaloud books open with narration
        vm.toggleSynchronizedNarration()
        assertTrue(vm.readerMode.value is ReaderMode.ReadingOnly)

        val third = vm.sentences.value[2]
        vm.setFollowingNarration(false)
        vm.onSentenceTapped(third)

        val synced = vm.awaitMode { it is ReaderMode.ReadaloudSynced } as ReaderMode.ReadaloudSynced
        assertEquals(SyncedPlaybackState.PLAYING, synced.playbackState)
        assertEquals(third.clipBeginMs, synced.audioPositionMs)
        assertEquals(third.elementId, synced.highlight?.elementId)
        assertEquals(third.clipBeginMs, audio.lastSeekMs)
        assertTrue(audio.isPlaying)
        assertTrue(vm.isFollowingNarration.value)

        val saved = eventually {
            checkpointDao.observeCheckpoint("b1", "s1", "u1").first { it?.audioPositionMs == third.clipBeginMs }
        }
        assertNotNull(saved)
        assertEquals(third.elementId, saved!!.activeSentenceId)
    }

    @Test
    fun `saved playback speed survives tap-to-speak and enabling narration`() = readerTest {
        preferences.updateSettings { it.copy(playbackSpeed = 1.5f) }
        seedBook()
        val vm = viewModel()
        eventually { vm.settings.first { it.playbackSpeed == 1.5f } }
        val opened = eventually {
            vm.readerMode.first { it is ReaderMode.ReadaloudSynced && it.playbackSpeed == 1.5f }
        } as ReaderMode.ReadaloudSynced
        assertEquals(1.5f, opened.playbackSpeed)

        vm.toggleSynchronizedNarration() // reading only
        vm.toggleSynchronizedNarration() // re-enable narration
        assertEquals(1.5f, (vm.readerMode.value as ReaderMode.ReadaloudSynced).playbackSpeed)

        vm.toggleSynchronizedNarration() // reading only
        vm.onSentenceTapped(vm.sentences.value[1])
        assertEquals(1.5f, (vm.readerMode.value as ReaderMode.ReadaloudSynced).playbackSpeed)
        // The hardware player was never told to change speed behind the UI's back.
        assertTrue(audio.speeds.none { it != 1.5f })
    }

    @Test
    fun `speed changes snap to 0_05 steps, clamp to range, and persist`() = readerTest {
        preferences.updateSettings { it.copy(playbackSpeed = 1.25f) }
        seedBook()
        val vm = viewModel()
        // Wait until the open sequence has applied the saved speed, so it can't race the change below.
        vm.awaitMode { it is ReaderMode.ReadaloudSynced && it.playbackSpeed == 1.25f }

        vm.setPlaybackSpeed(1.76f)
        assertEquals(1.75f, (vm.readerMode.value as ReaderMode.ReadaloudSynced).playbackSpeed)
        assertEquals(1.75f, audio.speeds.last())
        assertEquals(1.75f, eventually { preferences.settings.first { it.playbackSpeed == 1.75f } }.playbackSpeed)

        vm.setPlaybackSpeed(9f)
        assertEquals(3.5f, (vm.readerMode.value as ReaderMode.ReadaloudSynced).playbackSpeed)
        vm.setPlaybackSpeed(0.1f)
        assertEquals(0.5f, (vm.readerMode.value as ReaderMode.ReadaloudSynced).playbackSpeed)
        assertEquals(0.5f, eventually { preferences.settings.first { it.playbackSpeed == 0.5f } }.playbackSpeed)
    }

    @Test
    fun `leaving the reader persists the latest position`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        vm.seekToMs(42_000L)
        vm.togglePlayPause() // play: starts the 200ms position ticker

        // Playback moves on; the ticker picks it up without saving a checkpoint.
        audio.positionMs = 57_000L
        mainDispatcher.scheduler.advanceTimeBy(250L)
        mainDispatcher.scheduler.runCurrent()
        assertEquals(57_000L, (vm.readerMode.value as ReaderMode.ReadaloudSynced).audioPositionMs)

        store.clear() // user navigates back without pausing

        val saved = eventually {
            checkpointDao.observeCheckpoint("b1", "s1", "u1").first { it?.audioPositionMs == 57_000L }
        }
        assertEquals(57_000L, saved!!.audioPositionMs)
    }

    @Test
    fun `audio still playing when the reader closes carries on for the mini player`() = readerTest {
        seedBook(audioPath = audioFile())
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        vm.togglePlayPause()
        audio.positionMs = 57_000L

        store.clear()

        assertTrue(audio.isPlaying)
        assertEquals("b1", playback.nowPlaying.value?.book?.id)
        assertEquals(57_000L, playback.nowPlaying.value?.positionMs)
    }

    @Test
    fun `leaving the reader while paused keeps audio loaded in background for mini player`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        vm.togglePlayPause() // play
        vm.togglePlayPause() // pause

        store.clear()

        assertFalse(audio.stopped)
        val playing = playback.nowPlaying.value
        assertNotNull(playing)
        assertFalse(playing!!.isPlaying)
    }

    @Test
    fun `reopening a book that is playing in the background picks up the live position`() = readerTest {
        seedBook(audioPath = audioFile())
        val first = viewModel()
        first.awaitMode { it is ReaderMode.ReadaloudSynced }
        first.togglePlayPause()
        store.clear() // playing: hands off to the background session
        audio.positionMs = 90_000L

        val reopened = viewModel()
        val mode = reopened.awaitMode {
            it is ReaderMode.ReadaloudSynced && it.audioPositionMs == 90_000L
        } as ReaderMode.ReadaloudSynced
        assertEquals(SyncedPlaybackState.PLAYING, mode.playbackState)
        assertEquals(1, audio.prepareCount) // loaded on the first visit only, not reloaded
    }

    @Test
    fun `sleep timer set to the end of the chapter pauses there`() = readerTest {
        seedBook(audioPath = audioFile())
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        playback.sleepAt(20_000L)
        vm.togglePlayPause()

        audio.positionMs = 21_000L
        mainDispatcher.scheduler.advanceTimeBy(250L)
        mainDispatcher.scheduler.runCurrent()

        assertEquals(SyncedPlaybackState.PAUSED, (vm.readerMode.value as ReaderMode.ReadaloudSynced).playbackState)
        assertNull(playback.sleepAtMs.value)
    }

    @Test
    fun `pausing from the notification is picked up by the reader`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        vm.togglePlayPause()

        audio.isPlaying = false // e.g. the lock-screen pause button
        mainDispatcher.scheduler.advanceTimeBy(250L)
        mainDispatcher.scheduler.runCurrent()

        assertEquals(SyncedPlaybackState.PAUSED, (vm.readerMode.value as ReaderMode.ReadaloudSynced).playbackState)
    }

    @Test
    fun `ebook-only books open in reading mode and play-pause is a no-op`() = readerTest {
        seedBook(type = BookMediaType.EBOOK_ONLY)
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadingOnly }

        vm.togglePlayPause()

        assertTrue(vm.readerMode.value is ReaderMode.ReadingOnly)
    }

    @Test
    fun `long-press selects a word and dragging extends it within the same paragraph only`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        val first = vm.sentences.value[0]
        val second = vm.sentences.value[1]

        vm.startSelection(first, 1)
        val word = vm.selection.value!!
        assertTrue(word.isSingleWord)
        assertEquals(ReaderTextLogic.wordRangeAt(first.text, 1), word.range)

        val lastWord = ReaderTextLogic.wordRangeAt(first.text, first.text.length - 1)!!
        vm.extendSelection(first, first.text.length - 1)
        assertEquals(CharSpan(word.range.start, lastWord.end), vm.selection.value!!.range)

        // Dragging into another paragraph doesn't move the selection.
        vm.extendSelection(second, 0)
        assertEquals(first.elementId, vm.selection.value!!.sentence.elementId)
        assertEquals(CharSpan(word.range.start, lastWord.end), vm.selection.value!!.range)

        vm.clearSelection()
        assertNull(vm.selection.value)
    }

    @Test
    fun `highlighting saves offsets and chapter, and re-highlighting the same span replaces it`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        val sentence = vm.sentences.value[2]

        vm.startSelection(sentence, 0)
        val span = vm.selection.value!!.range
        vm.highlightSelection(HighlightColor.GREEN, note = "  remember this  ")
        assertNull(vm.selection.value)

        val saved = eventually { vm.annotations.first { it.isNotEmpty() } }.single()
        assertEquals(AnnotationType.HIGHLIGHT, saved.type)
        assertEquals(sentence.elementId, saved.elementId)
        assertEquals(sentence.textHref, saved.textHref)
        assertEquals(span.start, saved.startOffset)
        assertEquals(span.end, saved.endOffset)
        assertEquals(sentence.text.substring(span.start, span.end), saved.selectedText)
        assertEquals("remember this", saved.note)
        assertEquals(HighlightColor.GREEN, saved.color)

        vm.startSelection(sentence, 0)
        vm.highlightSelection(HighlightColor.PINK)
        val recolored = eventually { vm.annotations.first { list -> list.singleOrNull()?.color == HighlightColor.PINK } }.single()
        // Updated in place: same annotation, note and creation time kept.
        assertEquals(saved.id, recolored.id)
        assertEquals("remember this", recolored.note)
        assertEquals(saved.createdAt, recolored.createdAt)

        vm.editNote(recolored, "   ")
        val cleared = eventually { vm.annotations.first { list -> list.single().note == null } }.single()
        assertEquals(saved.id, cleared.id)
    }

    @Test
    fun `bookmark toggles on and off for a paragraph`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        val sentence = vm.sentences.value[1]

        vm.toggleBookmark(sentence)
        eventually { vm.annotations.first { list -> list.any { it.type == AnnotationType.BOOKMARK } } }
        assertTrue(vm.isBookmarked(sentence.elementId))

        vm.toggleBookmark(sentence)
        eventually { vm.annotations.first { it.isEmpty() } }
        assertTrue(!vm.isBookmarked(sentence.elementId))
    }

    @Test
    fun `define reports loading then the result, and maps not-found and network errors`() = readerTest {
        seedBook()
        val gate = CompletableDeferred<DictionaryResult>()
        lookup = { gate.await() }
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }

        vm.startSelection(vm.sentences.value[0], 1)
        vm.define("  serendipity ")
        assertEquals(DictionaryUiState.Loading("serendipity"), vm.dictionary.value)
        assertNull("defining clears the selection", vm.selection.value)

        val entry = DictionaryEntry(word = "serendipity", phonetic = "/ˌsɛr.ənˈdɪp.ɪ.ti/")
        gate.complete(DictionaryResult.Success(entry))
        assertEquals(DictionaryUiState.Loaded("serendipity", entry), eventually { vm.dictionary.first { it is DictionaryUiState.Loaded } })

        lookup = { DictionaryResult.NotFound(it) }
        vm.define("zzxq")
        assertEquals(DictionaryUiState.NotFound("zzxq"), eventually { vm.dictionary.first { it is DictionaryUiState.NotFound } })

        lookup = { DictionaryResult.NetworkError("offline") }
        vm.define("word")
        assertEquals(DictionaryUiState.Failed("word", "offline"), eventually { vm.dictionary.first { it is DictionaryUiState.Failed } })

        vm.dismissDictionary()
        assertNull(vm.dictionary.value)
    }

    @Test
    fun `a newer lookup cancels a slower older one so its result never shows`() = readerTest {
        seedBook()
        val slow = CompletableDeferred<DictionaryResult>()
        lookup = { word -> if (word == "slow") slow.await() else DictionaryResult.NotFound(word) }
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }

        vm.define("slow")
        vm.define("fast")
        eventually { vm.dictionary.first { it is DictionaryUiState.NotFound } }
        slow.complete(DictionaryResult.Success(DictionaryEntry(word = "slow")))
        assertEquals(DictionaryUiState.NotFound("fast"), vm.dictionary.value)
    }

    @Test
    fun `markdown export includes highlighted text and notes`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        val sentence = vm.sentences.value[0]
        vm.startSelection(sentence, 1)
        val text = vm.selection.value!!.text
        vm.highlightSelection(HighlightColor.YELLOW, note = "first note")
        eventually { vm.annotations.first { it.isNotEmpty() } }

        val markdown = vm.exportMarkdown()!!
        assertTrue(markdown.contains("Synced Book"))
        assertTrue(markdown.contains(text))
        assertTrue(markdown.contains("first note"))
    }

    @Test
    fun `a book missing from the library reports an error, and retry recovers once it appears`() = readerTest {
        val vm = viewModel(bookId = "b1") // not seeded yet
        assertEquals("Book not found in library", eventually { vm.sessionError.first { it != null } })
        assertTrue(vm.readerMode.value is ReaderMode.Idle || vm.readerMode.value is ReaderMode.Loading)

        seedBook()
        vm.retryOpen()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        assertNull(vm.sessionError.value)
    }

    @Test
    fun `an imported book's position is saved in the local scope even while a server is connected`() = readerTest {
        val id = LocalLibrary.ID_PREFIX + "imported"
        seedBook(id = id, type = BookMediaType.EBOOK_ONLY, serverId = LocalLibrary.SERVER_ID, userId = LocalLibrary.USER_ID)
        val vm = viewModel(bookId = id)
        vm.awaitMode { it is ReaderMode.ReadingOnly }

        store.clear() // leaving the reader persists the position

        val saved = eventually { checkpointDao.observeCheckpoint(id, LocalLibrary.SERVER_ID, LocalLibrary.USER_ID).first { it != null } }
        assertEquals(id, saved!!.bookId)
        // Nothing was written under the server's scope (on a device that would violate the books foreign key).
        assertNull(checkpointDao.getCheckpoint(id, "s1", "u1"))
    }

    @Test
    fun `undownloaded books show not downloaded state instead of placeholder text`() = readerTest {
        seedBook(filePath = null, downloadStatus = DownloadStatus.NOT_DOWNLOADED)
        val vm = viewModel()
        val mode = vm.awaitMode { it is ReaderMode.NotDownloaded } as ReaderMode.NotDownloaded
        assertEquals("b1", mode.book.id)
        assertEquals(DownloadStatus.NOT_DOWNLOADED, mode.book.downloadStatus)
        assertTrue(vm.sentences.value.isEmpty())
    }

    @Test
    fun `downloading an undownloaded book updates progress and opens into the reader on completion`() = readerTest {
        seedBook(filePath = null, downloadStatus = DownloadStatus.NOT_DOWNLOADED)
        // A moving clock, so each lastOpenedAt write really changes the row and re-emits the library.
        var now = 1_000L
        val client = NetworkModule.okHttpClient(ServerAuthInterceptor({ vault.activeProfile.value }) {}, enableLogging = false)
        repository = BookRepository(NetworkModule.storytellerApi(client), bookDao, checkpointDao, vault, clock = { ++now })
        var enqueued: Book? = null
        val fakeScheduler = object : org.hello.reader.feature.library.DownloadScheduler {
            override fun enqueue(book: Book, requestedMediaType: BookMediaType?) { enqueued = book }
            override fun cancel(book: Book) = Unit
        }
        val vm = ViewModelProvider.create(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReaderViewModel(
                        bookId = "b1",
                        bookRepository = repository,
                        preferencesRepository = preferences,
                        vault = vault,
                        annotationRepository = annotations,
                        lookupWord = { lookup(it) },
                        playback = playback,
                        persistenceScope = persistenceScope,
                        downloadScheduler = fakeScheduler,
                    ) as T
            },
        )[ReaderViewModel::class.java]

        vm.awaitMode { it is ReaderMode.NotDownloaded }

        vm.downloadBook()
        assertNotNull(enqueued)
        assertEquals("b1", enqueued!!.id)

        bookDao.updateDownloadStatus("b1", "s1", "u1", DownloadStatus.DOWNLOADING, 0.45f, null, null, null)
        val downloading = eventually {
            vm.readerMode.first { it is ReaderMode.NotDownloaded && it.book.downloadStatus == DownloadStatus.DOWNLOADING }
        } as ReaderMode.NotDownloaded
        assertEquals(0.45f, downloading.book.downloadProgress)

        val epubPath = twoChapterEpub()
        bookDao.updateDownloadStatus("b1", "s1", "u1", DownloadStatus.DOWNLOADED, 1.0f, epubPath, null, "hash")

        val opened = vm.awaitMode { it is ReaderMode.ReadaloudSynced } as ReaderMode.ReadaloudSynced
        assertEquals("b1", opened.book.id)
        assertTrue(vm.sentences.value.isNotEmpty())
        // Opening writes lastOpenedAt, which re-emits the library; that must not open the book again.
        vm.sessionLoaded.first { it }
        Thread.sleep(500)
        assertEquals(1, audio.prepareCount)
    }

    @Test
    fun `local open leaves resumedElementId null and clearResumeHighlight clears state`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        assertNull(vm.resumedElementId.value)
        assertNull(vm.resumedAudioNotice.value)

        vm.clearResumeHighlight()
        assertNull(vm.resumedElementId.value)
        assertNull(vm.resumedAudioNotice.value)
    }

    @Test
    fun `resolving conflict with useRemote sets resumedElementId and clearResumeHighlight clears it`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }

        val remotePos = org.hello.reader.core.model.SyncCheckpoint(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "c1/p2",
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            audioPositionMs = 2_500L,
            totalDurationMs = 600_000L,
            progressPercent = 5.0f,
            activeSentenceId = "s2",
            updatedAt = 1000L,
            isDirty = false,
        )
        val localPos = org.hello.reader.core.model.SyncCheckpoint(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "c1/p1",
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            audioPositionMs = 0L,
            totalDurationMs = 600_000L,
            progressPercent = 0.0f,
            activeSentenceId = "s1",
            updatedAt = 500L,
            isDirty = false,
        )
        val conflict = org.hello.reader.core.model.SyncConflict(
            bookId = "b1",
            local = localPos,
            remote = remotePos,
        )
        repository.recordConflict(conflict)
        assertEquals(conflict, vm.activeConflict.first { it != null })

        vm.resolveConflict(useRemote = true)
        eventually {
            assertEquals("s2", vm.resumedElementId.value)
        }

        vm.clearResumeHighlight()
        assertNull(vm.resumedElementId.value)
    }

    private class FakeAudioController : AudioPlaybackController {
        var positionMs: Long? = null
        var lastSeekMs: Long? = null
        var isPlaying = false
        var stopped = false
        var prepareCount = 0
        val speeds = mutableListOf<Float>()
        var lastPreparedFile: File? = null
        var lastPreparedTracks: List<org.hello.reader.core.engine.smil.AudioTrackItem> = emptyList()

        override val hasLoadedMedia: Boolean = true
        override fun prepareFile(
            audioFile: File,
            title: String,
            artist: String?,
            artworkUri: String?,
            initialPositionMs: Long,
            playbackSpeed: Float,
        ) {
            prepareCount++
            lastPreparedFile = audioFile
        }

        override fun prepareTracks(
            tracks: List<org.hello.reader.core.engine.smil.AudioTrackItem>,
            title: String,
            artist: String?,
            artworkUri: String?,
            initialPositionMs: Long,
            playbackSpeed: Float,
            bookId: String?,
        ) {
            prepareCount++
            lastPreparedTracks = tracks
            lastPreparedFile = tracks.firstOrNull()?.file
        }
        override fun play() { isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun seekTo(positionMs: Long) {
            lastSeekMs = positionMs
            this.positionMs = positionMs
        }
        override fun setSpeed(speed: Float) { speeds += speed }
        override fun setVolume(volume: Float) = Unit
        override fun currentPositionMs(): Long? = positionMs
        override fun isPlaybackRequested(): Boolean = isPlaying
        override fun stop() {
            isPlaying = false
            stopped = true
        }
        override fun release() = Unit
    }

    private fun plainEpub(): String {
        val file = tmp.newFile("plain-${System.currentTimeMillis()}.epub")
        fun xhtml(chapter: Int) =
            """<html><body><p id="c${chapter}s1">Chapter $chapter opens.</p><p id="c${chapter}s2">Chapter $chapter closes.</p></body></html>"""
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("OEBPS/nav.xhtml", """<nav epub:type="toc"><a href="Text/ch1.xhtml">Arrival</a><a href="Text/ch2.xhtml">Departure</a></nav>""")
            put("OEBPS/Text/ch1.xhtml", xhtml(1))
            put("OEBPS/Text/ch2.xhtml", xhtml(2))
        }
        return file.path
    }

    private fun audiobookDir(): File {
        val dir = tmp.newFolder("audiobook-${System.currentTimeMillis()}")
        File(dir, "01.mp3").writeText("ID3_track1")
        File(dir, "02.mp3").writeText("ID3_track2")
        return dir
    }

    @Test
    fun `readaloud-only opens with SMIL audio extracted from the EPUB`() = readerTest {
        val epubPath = twoChapterEpub()
        seedBook(id = "b_ro", type = BookMediaType.READALOUD, filePath = epubPath, audioPath = null)
        downloadDao.upsertDownload(
            BookDownloadEntity(
                bookId = "b_ro",
                serverId = "s1",
                userId = "u1",
                format = BookMediaType.READALOUD,
                localPath = epubPath,
                downloadStatus = DownloadStatus.DOWNLOADED,
            ),
        )

        val vm = viewModel("b_ro")
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }

        assertEquals(4, vm.sentences.value.size)
        assertNotNull(audio.lastPreparedFile)
        assertTrue(
            "Prepared audio must be an extracted audio file, not the EPUB container",
            audio.lastPreparedFile!!.name.endsWith(".mp3", ignoreCase = true) &&
                !audio.lastPreparedFile!!.name.endsWith(".epub", ignoreCase = true),
        )
    }

    @Test
    fun `readaloud and audiobook downloaded still uses the readaloud's own tracks`() = readerTest {
        val epubPath = twoChapterEpub()
        val abDir = audiobookDir()
        seedBook(id = "b_both", type = BookMediaType.READALOUD, filePath = epubPath, audioPath = abDir.absolutePath)
        downloadDao.upsertDownload(
            BookDownloadEntity(
                bookId = "b_both",
                serverId = "s1",
                userId = "u1",
                format = BookMediaType.READALOUD,
                localPath = epubPath,
                downloadStatus = DownloadStatus.DOWNLOADED,
            ),
        )
        downloadDao.upsertDownload(
            BookDownloadEntity(
                bookId = "b_both",
                serverId = "s1",
                userId = "u1",
                format = BookMediaType.AUDIO_ONLY,
                localPath = abDir.absolutePath,
                downloadStatus = DownloadStatus.DOWNLOADED,
            ),
        )

        val vm = viewModel("b_both")
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }

        assertEquals(4, vm.sentences.value.size)
        assertNotNull(audio.lastPreparedFile)
        assertTrue(
            "Readaloud must use its own extracted tracks for sync, not external audiobook tracks",
            audio.lastPreparedTracks.any { it.audioSrc.contains("ch1") || it.file.parentFile != abDir },
        )
    }

    @Test
    fun `ebook and audiobook without readaloud uses the audiobook for listening`() = readerTest {
        val epubPath = plainEpub()
        val abDir = audiobookDir()
        seedBook(
            id = "b_hybrid",
            type = BookMediaType.EBOOK_ONLY,
            filePath = epubPath,
            audioPath = abDir.absolutePath,
            hasAudiobook = true,
            hasEbook = true,
            hasReadaloud = false,
        )
        downloadDao.upsertDownload(
            BookDownloadEntity(
                bookId = "b_hybrid",
                serverId = "s1",
                userId = "u1",
                format = BookMediaType.EBOOK_ONLY,
                localPath = epubPath,
                downloadStatus = DownloadStatus.DOWNLOADED,
            ),
        )
        downloadDao.upsertDownload(
            BookDownloadEntity(
                bookId = "b_hybrid",
                serverId = "s1",
                userId = "u1",
                format = BookMediaType.AUDIO_ONLY,
                localPath = abDir.absolutePath,
                downloadStatus = DownloadStatus.DOWNLOADED,
            ),
        )

        val vm = viewModel("b_hybrid")
        vm.awaitMode { it is ReaderMode.ReadingOnly }

        assertTrue(vm.sentences.value.isNotEmpty())
        assertNotNull(audio.lastPreparedFile)
        assertTrue(
            "Ebook without readaloud must use the standalone audiobook files",
            audio.lastPreparedTracks.any { it.file.parentFile == abDir || it.file.name == "01.mp3" },
        )
    }

    @Test
    fun `jumpToActiveNarration emits active elementId to scrollRequests without altering playback`() = readerTest {
        seedBook()
        val vm = viewModel()
        val synced = vm.awaitMode { it is ReaderMode.ReadaloudSynced } as ReaderMode.ReadaloudSynced
        val activeId = synced.highlight?.elementId
        assertNotNull(activeId)

        val initialSeek = audio.lastSeekMs
        val isPlayingBefore = audio.isPlaying

        val received = CompletableDeferred<ReaderScrollTarget>()
        val job = launch(mainDispatcher) {
            vm.scrollRequests.collect { received.complete(it) }
        }

        vm.setFollowingNarration(false)
        vm.jumpToActiveNarration()

        val target = received.await()
        assertEquals(activeId, target.elementId)
        assertEquals(false, target.pauseAutoFollow)
        assertTrue(vm.isFollowingNarration.value)
        // Audio playback must be untouched: no seeking, no pause/play toggle
        assertEquals(initialSeek, audio.lastSeekMs)
        assertEquals(isPlayingBefore, audio.isPlaying)
        job.cancel()
    }

    @Test
    fun `jumpToSearchResult sets resume highlight and emits scroll target with paused auto-follow`() = readerTest {
        seedBook()
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }

        val received = CompletableDeferred<ReaderScrollTarget>()
        val job = launch(mainDispatcher) {
            vm.scrollRequests.collect { received.complete(it) }
        }

        vm.jumpToSearchResult("target_sentence_id")

        val target = received.await()
        assertEquals("target_sentence_id", target.elementId)
        assertTrue(target.pauseAutoFollow)
        assertEquals("target_sentence_id", vm.resumedElementId.value)
        job.cancel()
    }

    @Test
    fun `server-offered readaloud that is not downloaded keeps the downloaded ebook's mode`() = readerTest {
        seedBook(id = "b1", type = BookMediaType.EBOOK_ONLY, hasReadaloud = true)

        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadingOnly }

        val stored = repository.observeLibrary().first().first { it.id == "b1" }
        assertEquals(BookMediaType.EBOOK_ONLY, stored.mediaType)
    }

    @Test
    fun `readaloud book with corrupted mediaType EBOOK_ONLY is auto-healed to READALOUD and opens in ReadaloudSynced`() = readerTest {
        val epubPath = twoChapterEpub()
        seedBook(
            id = "b1",
            type = BookMediaType.EBOOK_ONLY,
            filePath = epubPath,
            hasReadaloud = true,
        )
        downloadDao.upsertDownload(
            BookDownloadEntity(
                bookId = "b1",
                serverId = "s1",
                userId = "u1",
                format = BookMediaType.READALOUD,
                localPath = epubPath,
                downloadStatus = DownloadStatus.DOWNLOADED,
            ),
        )
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        val mode = vm.readerMode.value as ReaderMode.ReadaloudSynced
        assertEquals(BookMediaType.READALOUD, mode.book.mediaType)

        val updated = repository.observeLibrary().first().first { it.id == "b1" }
        assertEquals(BookMediaType.READALOUD, updated.mediaType)
    }

    @Test
    fun `readaloud book with corrupted mediaType AUDIO_ONLY is auto-healed to READALOUD and opens in ReadaloudSynced`() = readerTest {
        val epubPath = twoChapterEpub()
        seedBook(
            id = "b1",
            type = BookMediaType.AUDIO_ONLY,
            filePath = epubPath,
            hasReadaloud = true,
        )
        downloadDao.upsertDownload(
            BookDownloadEntity(
                bookId = "b1",
                serverId = "s1",
                userId = "u1",
                format = BookMediaType.READALOUD,
                localPath = epubPath,
                downloadStatus = DownloadStatus.DOWNLOADED,
            ),
        )
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }
        val mode = vm.readerMode.value as ReaderMode.ReadaloudSynced
        assertEquals(BookMediaType.READALOUD, mode.book.mediaType)

        val updated = repository.observeLibrary().first().first { it.id == "b1" }
        assertEquals(BookMediaType.READALOUD, updated.mediaType)
    }

    @Test
    fun `opening or paging a to_read book leaves the status for the server to promote`() = readerTest {
        seedBook(
            id = "b1",
            type = BookMediaType.EBOOK_ONLY,
            readingStatus = "to_read",
            readingStatusLabel = "To read",
        )
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadingOnly }

        // Restoring the page reports whatever paragraph is first on screen.
        vm.onVisibleElementChanged("c1s1")
        vm.onVisibleElementChanged("c2s1")

        val book = bookDao.getBookById("b1", "s1", "u1")!!
        assertEquals("to_read", book.readingStatus)
        assertEquals("To read", book.readingStatusLabel)
    }

    @Test
    fun `playing narration on a to_read book promotes it to reading`() = readerTest {
        seedBook(
            id = "b1",
            type = BookMediaType.READALOUD,
            readingStatus = "to_read",
            readingStatusLabel = "To read",
        )
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }

        val beforePlay = bookDao.getBookById("b1", "s1", "u1")!!
        assertEquals("to_read", beforePlay.readingStatus)

        vm.play()

        val promoted = eventually {
            bookDao.observeBookById("b1", "s1", "u1").first { it?.readingStatus == "reading" }!!
        }
        assertEquals("Reading", promoted.readingStatusLabel)
    }
}
