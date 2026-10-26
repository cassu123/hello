package org.hello.reader.feature.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import org.hello.reader.core.data.AnnotationRepository
import org.hello.reader.core.data.BookRepository
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.engine.audio.AudioPlaybackController
import org.hello.reader.core.engine.audio.PlaybackSession
import org.hello.reader.core.engine.reader.ReaderMode
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.engine.smil.ChapterParagraph
import org.hello.reader.core.engine.smil.EpubOverlayExtractor
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class StorytellerRealisticFixtureTest {

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
        filePath: String,
    ) = runBlocking {
        bookDao.upsertBooks(
            listOf(
                BookEntity(
                    id = id, serverId = "s1", userId = "u1", title = "Realistic Storyteller Book", authors = "[\"River Song\"]",
                    narrators = "[]", coverUrl = null, description = null, mediaType = type, durationSeconds = 3600L,
                    totalChapters = 32, downloadStatus = DownloadStatus.DOWNLOADED, downloadProgress = 1f,
                    localFilePath = filePath, tags = "[]", createdAt = 0L, lastOpenedAt = null, localAudioPath = null,
                    hasAudiobook = false, hasEbook = true, hasReadaloud = true,
                ),
            ),
        )
    }

    private fun readerTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            store.clear()
            sessionScope.cancel()
        }
    }

    private suspend fun <T> eventually(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(15_000) { block() } }

    private suspend fun ReaderViewModel.awaitMode(predicate: (ReaderMode) -> Boolean): ReaderMode =
        eventually {
            sessionLoaded.first { it }
            readerMode.first(predicate)
        }

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
                        lookupWord = { DictionaryResult.NotFound(it) },
                        playback = playback,
                        persistenceScope = persistenceScope,
                    ) as T
            },
        )[ReaderViewModel::class.java]

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

    /**
     * Builds a realistic 32-chapter Storyteller EPUB with:
     * - Front matter (titlepage, copyright) without TOC entries
     * - 32 chapters
     * - TOC hrefs with and without `#fragment` identifiers
     * - SMIL sync with slight overlaps at chapter boundaries (e.g. 1.5s boundary overlap)
     */
    private fun createRealisticStorytellerEpub(): File {
        val file = tmp.newFile("storyteller-realistic.epub")
        val chapterCount = 32

        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.trimIndent().toByteArray())
                zip.closeEntry()
            }

            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container version="1.0"><rootfiles><rootfile full-path="OEBPS/package.opf"/></rootfiles></container>""",
            )

            // Manifest & spine
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()

            manifestItems.append("""<item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>""").append("\n")
            manifestItems.append("""<item id="titlepage" href="Text/titlepage.xhtml" media-type="application/xhtml+xml"/>""").append("\n")
            manifestItems.append("""<item id="copyright" href="Text/copyright.xhtml" media-type="application/xhtml+xml"/>""").append("\n")
            manifestItems.append("""<item id="audio_main" href="Audio/narration.mp3" media-type="audio/mpeg"/>""").append("\n")

            spineItems.append("""<itemref idref="titlepage"/>""").append("\n")
            spineItems.append("""<itemref idref="copyright"/>""").append("\n")

            for (i in 1..chapterCount) {
                manifestItems.append("""<item id="c$i" href="Text/ch$i.xhtml" media-overlay="s$i" media-type="application/xhtml+xml"/>""").append("\n")
                manifestItems.append("""<item id="s$i" href="Smil/ch$i.smil" media-type="application/smil+xml"/>""").append("\n")
                spineItems.append("""<itemref idref="c$i"/>""").append("\n")
            }

            put(
                "OEBPS/package.opf",
                """
                <package>
                  <manifest>
                    $manifestItems
                  </manifest>
                  <spine>
                    $spineItems
                  </spine>
                </package>
                """,
            )

            // Nav with TOC: front matter omitted; chapters alternate between #fragment and no fragment
            val navList = StringBuilder()
            for (i in 1..chapterCount) {
                val href = if (i % 2 == 1) "Text/ch$i.xhtml#header_$i" else "Text/ch$i.xhtml"
                navList.append("""<li><a href="$href">Chapter $i: The Journey $i</a></li>""").append("\n")
            }
            put(
                "OEBPS/nav.xhtml",
                """
                <html xmlns:epub="http://www.idpf.org/2007/ops"><nav epub:type="toc"><ol>
                  $navList
                </ol></nav></html>
                """,
            )

            // Front matter XHTML
            put(
                "OEBPS/Text/titlepage.xhtml",
                """<html><body><h1 id="title_head">The Great Epic</h1><p id="title_sub">A Storyteller Tale</p></body></html>""",
            )
            put(
                "OEBPS/Text/copyright.xhtml",
                """<html><body><p id="copy_1">Copyright 2026 Author.</p><p id="copy_2">All rights reserved.</p></body></html>""",
            )

            // Chapter XHTML & SMIL
            var currentOffsetMs = 0L
            for (i in 1..chapterCount) {
                put(
                    "OEBPS/Text/ch$i.xhtml",
                    """
                    <html><body>
                      <h2 id="header_$i">Chapter $i: The Journey $i</h2>
                      <p id="c${i}_s1">First sentence of chapter $i describing the world.</p>
                      <p id="c${i}_s2">Second sentence of chapter $i continuing the adventure.</p>
                    </body></html>
                    """,
                )

                // Overlap: Chapter 4 and Chapter 10 have 1.5s boundary overlap with previous chapter
                val startMs = if (i > 1 && (i == 4 || i == 10)) {
                    (currentOffsetMs - 1500L).coerceAtLeast(0L)
                } else {
                    currentOffsetMs
                }
                val midMs = startMs + 10_000L
                val endMs = midMs + 10_000L
                currentOffsetMs = endMs

                val smilContent = """
                    <smil><body>
                      <par><text src="../Text/ch$i.xhtml#header_$i"/><audio src="../Audio/narration.mp3" clipBegin="${startMs}ms" clipEnd="${startMs + 2000L}ms"/></par>
                      <par><text src="../Text/ch$i.xhtml#c${i}_s1"/><audio src="../Audio/narration.mp3" clipBegin="${startMs + 2000L}ms" clipEnd="${midMs}ms"/></par>
                      <par><text src="../Text/ch$i.xhtml#c${i}_s2"/><audio src="../Audio/narration.mp3" clipBegin="${midMs}ms" clipEnd="${endMs}ms"/></par>
                    </body></smil>
                """
                put("OEBPS/Smil/ch$i.smil", smilContent)
            }

            put("OEBPS/Audio/narration.mp3", "ID3_TEST_AUDIO")
        }
        return file
    }

    @Test
    fun `BookChapters preserves timing and all chapters across TOC fragments and SMIL boundary overlaps`() {
        val epub = createRealisticStorytellerEpub()
        val extractorResult = EpubOverlayExtractor.extract(epub)
        assertNotNull(extractorResult)
        val extracted = extractorResult!!

        val paragraphs = extracted.sentences.map {
            ChapterParagraph(it.elementId, it.textHref, it.text, it.clipBeginMs, it.clipEndMs)
        }

        val chapters = BookChapters.build(
            paragraphs = paragraphs,
            tocTitles = extracted.tocTitles,
            timed = true,
        )

        assertTrue("Chapters must have timing", chapters.hasTiming)
        assertTrue("Should have at least 32 chapters, got ${chapters.size}", chapters.size >= 32)
        assertNotNull("Chapter 4 startMs must not be null", chapters[3]?.startMs)
    }

    @Test
    fun `navigating to chapters in realistic book seeks audio and turns page`() = readerTest {
        val epub = createRealisticStorytellerEpub()
        seedBook(filePath = epub.path)
        val vm = viewModel()
        vm.awaitMode { it is ReaderMode.ReadaloudSynced }

        val testChapters = listOf(2, 4, 15, 30)
        for (chIndex in testChapters) {
            val chapter = vm.chapters.value[chIndex]
            assertNotNull("Chapter $chIndex must exist", chapter)
            assertNotNull("Chapter $chIndex must have startMs", chapter!!.startMs)

            val scrollRequest = backgroundScope.async(Dispatchers.Unconfined) { vm.scrollRequests.first() }
            vm.goToChapter(chIndex)

            assertEquals("Audio seek must match chapter startMs", chapter.startMs, audio.lastSeekMs)
            val req = scrollRequest.await()
            assertEquals("Scroll request must match chapter firstElementId", chapter.firstElementId, req.elementId)
            assertTrue("Follow narration must be true", vm.isFollowingNarration.value)
        }
    }
}
