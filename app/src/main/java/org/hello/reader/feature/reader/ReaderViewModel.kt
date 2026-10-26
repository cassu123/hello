package org.hello.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import org.hello.reader.app.HelloApplication
import org.hello.reader.core.data.AnnotationRepository
import org.hello.reader.core.data.BookRepository
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.engine.BookContentLoader
import org.hello.reader.core.engine.LoadedBookContent
import org.hello.reader.core.engine.audio.AudioPlaybackController
import org.hello.reader.core.engine.audio.BackgroundHandoff
import org.hello.reader.core.engine.audio.NowPlaying
import org.hello.reader.core.engine.audio.PlaybackOwner
import org.hello.reader.core.engine.audio.PlaybackSession
import org.hello.reader.core.engine.reader.ReaderMode
import org.hello.reader.core.engine.reader.ReaderStateMachine
import org.hello.reader.core.engine.reader.SyncedPlaybackState
import org.hello.reader.core.engine.smil.AudioTrackItem
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.engine.smil.ChapterParagraph
import org.hello.reader.core.engine.smil.EpubOverlayExtractor
import org.hello.reader.core.engine.smil.SmilParNode
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookAnnotation
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.core.model.LocalLibrary
import org.hello.reader.core.model.ReadingStatusItem
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.core.model.isLocal
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.core.model.SyncConflict
import org.hello.reader.core.network.DictionaryEntry
import org.hello.reader.core.network.DictionaryResult
import org.hello.reader.core.security.SecurityVault
import org.hello.reader.core.util.NaturalFileOrder
import org.hello.reader.feature.library.DownloadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderScrollTarget(
    val elementId: String,
    val pauseAutoFollow: Boolean = false,
)

data class ReaderSentenceItem(
    val elementId: String,
    val textHref: String,
    val text: String,
    val clipBeginMs: Long,
    val clipEndMs: Long,
    val isChapterStart: Boolean = false,
    val chapterTitle: String? = null,
    val rawHtmlId: String? = null,
)

sealed interface DictionaryUiState {
    val word: String

    data class Loading(override val word: String) : DictionaryUiState
    data class Loaded(override val word: String, val entry: DictionaryEntry) : DictionaryUiState
    data class NotFound(override val word: String) : DictionaryUiState
    data class Failed(override val word: String, val message: String) : DictionaryUiState
}

data class ResumedAudioNotice(
    val chapterTitle: String?,
    val chapterIndex: Int,
    val positionMs: Long,
)

class ReaderViewModel(
    private val bookId: String,
    private val bookRepository: BookRepository,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val vault: SecurityVault,
    private val annotationRepository: AnnotationRepository,
    /** Free Dictionary lookup; a function so tests don't hit the network. */
    private val lookupWord: suspend (String) -> DictionaryResult,
    /** App-wide playback, so narration can carry on after the reader closes. */
    private val playback: PlaybackSession,
    private val stateMachine: ReaderStateMachine = ReaderStateMachine(),
    /**
     * Outlives this ViewModel: the final checkpoint is written from [onCleared],
     * after [viewModelScope] has already been cancelled.
     */
    private val persistenceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val downloadScheduler: DownloadScheduler? = null,
) : ViewModel() {

    /**
     * The user's chosen speed. [ReaderStateMachine] rebuilds its synced state on
     * some transitions (tap-to-speak, enabling narration) at the default speed,
     * so it is re-applied after each of them.
     */
    private var preferredSpeed: Float = ReaderStateMachine.DEFAULT_SPEED

    private val audioController: AudioPlaybackController get() = playback.controller

    private val owner = object : PlaybackOwner {
        override fun pauseFromSession() = pause()
        override fun togglePlayPause() = this@ReaderViewModel.togglePlayPause()
        override fun previousChapter() = this@ReaderViewModel.previousChapter()
        override fun nextChapter() = this@ReaderViewModel.nextChapter()
        override fun skipBy(deltaMs: Long) = this@ReaderViewModel.skipByMs(deltaMs)
    }

    val readerMode: StateFlow<ReaderMode> = stateMachine.state

    val settings: StateFlow<ReaderSettings> = preferencesRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    private val _sentences = MutableStateFlow<List<ReaderSentenceItem>>(emptyList())
    val sentences: StateFlow<List<ReaderSentenceItem>> = _sentences.asStateFlow()

    private val _chapters = MutableStateFlow(BookChapters.EMPTY)

    /** Table of contents, with narration timing when the book's audio lines up with it. */
    val chapters: StateFlow<BookChapters> = _chapters.asStateFlow()

    private val _readingAnchor = MutableStateFlow<String?>(null)

    /**
     * Paragraph the text should show while reading silently: the saved position on open and where
     * narration stopped. Scrolling doesn't change it, so the text never chases its own position.
     */
    val readingAnchor: StateFlow<String?> = _readingAnchor.asStateFlow()

    private val _resumedElementId = MutableStateFlow<String?>(null)

    /** The element ID of the remote resumed sentence/paragraph for a transient "where you left off" highlight. */
    val resumedElementId: StateFlow<String?> = _resumedElementId.asStateFlow()

    private val _resumedAudioNotice = MutableStateFlow<ResumedAudioNotice?>(null)

    /** Transient toast/notice for audiobook-only remote resume. */
    val resumedAudioNotice: StateFlow<ResumedAudioNotice?> = _resumedAudioNotice.asStateFlow()

    private val _isFollowingNarration = MutableStateFlow(true)

    /**
     * True while the reader viewport automatically turns pages to follow the active audio narration.
     * Set to false when the user turns a page or scrolls away while audio is playing, keeping that
     * page fixed while audio continues out of view.
     */
    val isFollowingNarration: StateFlow<Boolean> = _isFollowingNarration.asStateFlow()

    fun setFollowingNarration(following: Boolean) {
        _isFollowingNarration.value = following
    }

    fun clearResumeHighlight() {
        _resumedElementId.value = null
        _resumedAudioNotice.value = null
    }

    val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs

    /** True while "end of chapter" is set on the sleep timer. */
    val sleepAtChapterEnd: StateFlow<Boolean> = playback.sleepAtMs
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val activeConflict: StateFlow<SyncConflict?> = bookRepository.pendingConflicts
        .map { list -> list.firstOrNull { it.bookId == bookId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Highlights and bookmarks for this book, for painting the text and the annotations sheet. */
    val annotations: StateFlow<List<BookAnnotation>> = annotationRepository.observeAnnotations(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selection = MutableStateFlow<ReaderSelection?>(null)
    val selection: StateFlow<ReaderSelection?> = _selection.asStateFlow()

    private val _dictionary = MutableStateFlow<DictionaryUiState?>(null)
    val dictionary: StateFlow<DictionaryUiState?> = _dictionary.asStateFlow()
    private var lookupJob: Job? = null

    private val _scrollRequests = MutableSharedFlow<ReaderScrollTarget>(extraBufferCapacity = 1)

    /** Targets the UI should bring into view (jump-to from annotations, active narration, or search). */
    val scrollRequests: SharedFlow<ReaderScrollTarget> = _scrollRequests.asSharedFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<BookSearchState>(BookSearchState.Idle)
    val searchResults: StateFlow<BookSearchState> = _searchResults.asStateFlow()
    private var cachedSearchSentencesSource: List<ReaderSentenceItem>? = null
    private var cachedSearchSentences: List<SearchableSentence> = emptyList()

    private suspend fun getOrCreateSearchableSentences(sentences: List<ReaderSentenceItem>): List<SearchableSentence> {
        if (cachedSearchSentencesSource === sentences && cachedSearchSentences.isNotEmpty()) {
            return cachedSearchSentences
        }
        return withContext(Dispatchers.Default) {
            val prepared = BookSearchMatcher.prepareSentences(sentences)
            cachedSearchSentencesSource = sentences
            cachedSearchSentences = prepared
            prepared
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearchQuery() {
        _searchQuery.value = ""
    }

    fun jumpToSearchResult(elementId: String) {
        _resumedElementId.value = elementId
        _scrollRequests.tryEmit(ReaderScrollTarget(elementId = elementId, pauseAutoFollow = true))
    }

    private val _sessionLoaded = MutableStateFlow(false)

    /** True once content is loaded and the position has been reconciled with the server. */
    val sessionLoaded: StateFlow<Boolean> = _sessionLoaded.asStateFlow()

    private val _sessionError = MutableStateFlow<String?>(null)

    /** Surfaces a human-readable error when the book session cannot be loaded. */
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    private var playbackTickerJob: Job? = null
    private var sessionJob: Job? = null
    private var readingSaveJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var loadedSmilNodes: List<SmilParNode> = emptyList()
    private var loadedAudioTracks: List<AudioTrackItem> = emptyList()
    private var hasPromotedToReadingThisSession = false

    init {
        openBookSession()
        viewModelScope.launch {
            stateMachine.state.collect { mode ->
                syncChapter(mode)
                playback.publish(owner, nowPlayingOf(mode))
            }
        }
        viewModelScope.launch {
            bookRepository.observeLibrary().collect { books ->
                val book = books.firstOrNull { it.id == bookId } ?: return@collect
                val currentMode = stateMachine.state.value
                if (currentMode is ReaderMode.NotDownloaded) {
                    val hasLocal = !book.localFilePath.isNullOrBlank() || !book.localAudioPath.isNullOrBlank()
                    if (book.downloadStatus == DownloadStatus.DOWNLOADED && hasLocal) {
                        openBookSession()
                    } else {
                        stateMachine.setNotDownloaded(book)
                    }
                }
            }
        }
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _searchQuery
                .debounce(250)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val trimmed = query.trim()
                    if (trimmed.isEmpty()) {
                        _searchResults.value = BookSearchState.Idle
                        return@collectLatest
                    }
                    _searchResults.value = BookSearchState.Searching
                    val chapters = _chapters.value
                    val currentSentences = _sentences.value
                    val searchable = getOrCreateSearchableSentences(currentSentences)
                    val results = withContext(Dispatchers.Default) {
                        BookSearchMatcher.search(
                            searchableSentences = searchable,
                            chapters = chapters,
                            query = trimmed,
                            coroutineContext = coroutineContext,
                        )
                    }
                    val totalMatches = results.sumOf { it.results.size }
                    _searchResults.value = if (totalMatches > 0) {
                        BookSearchState.Results(trimmed, totalMatches, results)
                    } else {
                        BookSearchState.Empty(trimmed)
                    }
                }
        }
    }

    fun downloadBook(format: BookMediaType? = null) {
        viewModelScope.launch {
            val books = bookRepository.observeLibrary().first()
            val book = books.firstOrNull { it.id == bookId } ?: return@launch
            bookRepository.markDownloadQueued(book)
            downloadScheduler?.enqueue(book, format)
        }
    }

    fun cancelDownload() {
        viewModelScope.launch {
            val books = bookRepository.observeLibrary().first()
            val book = books.firstOrNull { it.id == bookId } ?: return@launch
            downloadScheduler?.cancel(book)
            bookRepository.markDownloadCancelled(book)
        }
    }

    private fun openBookSession() {
        // Opening writes lastOpenedAt, which re-emits the library; one open at a time.
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            try {
                bookRepository.markBookOpened(bookId)
                val books = bookRepository.observeLibrary().first()
                val book = books.firstOrNull { it.id == bookId }
                if (book == null) {
                    _sessionError.value = "Book not found in library"
                    _sessionLoaded.value = true
                    return@launch
                }
                // Still playing from an earlier visit (mini player): carry on rather than reload.
                val live = playback.attach(bookId, owner)
                val checkpoints = bookRepository.observeCheckpoints().first()
                val localCheckpoint = checkpoints[bookId]

                val downloads = bookRepository.observeDownloadsForBook(bookId).firstOrNull() ?: emptyList()
                val downloadedMap = downloads.filter { it.downloadStatus == DownloadStatus.DOWNLOADED }.associateBy { it.format }

                val readaloudDownload = downloadedMap[BookMediaType.READALOUD]
                val ebookDownload = downloadedMap[BookMediaType.EBOOK_ONLY]
                val audioDownload = downloadedMap[BookMediaType.AUDIO_ONLY]

                val resolvedEpubPath = readaloudDownload?.localPath
                    ?: ebookDownload?.localPath
                    ?: book.localFilePath

                // When a readaloud download exists (or book is a readaloud), open it with audioPath = null
                // so embedded SMIL audio from the EPUB is extracted and used.
                // Use the AUDIO_ONLY path only for audio-only listening, when no readaloud is downloaded.
                // `book.hasReadaloud` only says the server offers one, so it must not count here.
                val hasReadaloud = readaloudDownload != null || (book.mediaType == BookMediaType.READALOUD && book.localFilePath != null)
                val effectiveMediaType = if (hasReadaloud) BookMediaType.READALOUD else book.mediaType
                val currentBook = if (effectiveMediaType != book.mediaType) {
                    book.copy(mediaType = effectiveMediaType).also { healed ->
                        launch {
                            bookRepository.updateBookMediaType(healed, effectiveMediaType)
                        }
                    }
                } else {
                    book
                }
                val resolvedAudioPath = if (hasReadaloud) {
                    null
                } else {
                    audioDownload?.localPath
                        ?: currentBook.localAudioPath?.takeIf { !it.endsWith(".epub", ignoreCase = true) }
                }

                // Unzipping copies every narration track out of the EPUB; keep it off the main thread.
                val content = withContext(Dispatchers.IO) { BookContentLoader.loadBookContent(currentBook, resolvedEpubPath, resolvedAudioPath) }
                if (content == null) {
                    val hasLocal = !resolvedEpubPath.isNullOrBlank() || !resolvedAudioPath.isNullOrBlank()
                    if (currentBook.downloadStatus != DownloadStatus.DOWNLOADED || !hasLocal) {
                        stateMachine.setNotDownloaded(currentBook)
                        _sessionLoaded.value = true
                        return@launch
                    } else {
                        _sessionError.value = "Unable to read book content"
                        _sessionLoaded.value = true
                        return@launch
                    }
                }
                loadedSmilNodes = content.smilNodes
                loadedAudioTracks = content.audioTracks
                _sentences.value = content.sentences
                cachedSearchSentencesSource = null
                cachedSearchSentences = emptyList()
                _chapters.value = if (content.sentences.isNotEmpty()) {
                    BookChapters.build(
                        paragraphs = content.sentences.map {
                            ChapterParagraph(it.elementId, it.textHref, it.text, it.clipBeginMs, it.clipEndMs)
                        },
                        tocTitles = content.tocTitles,
                        timed = (content.audioFile != null || content.audioTracks.isNotEmpty()) && currentBook.mediaType != BookMediaType.EBOOK_ONLY,
                    )
                } else if (content.audioTracks.isNotEmpty()) {
                    BookChapters.buildFromTracks(content.audioTracks)
                } else {
                    BookChapters.EMPTY
                }

                val savedSettings = preferencesRepository.settings.first()
                preferredSpeed = ReaderStateMachine.snapSpeed(savedSettings.playbackSpeed)
                val resolvedLocal = resolveCheckpoint(localCheckpoint, content)
                if (content.audioTracks.isNotEmpty() && live == null) {
                    audioController.prepareTracks(
                        tracks = content.audioTracks,
                        title = currentBook.title,
                        artist = currentBook.authors.joinToString().ifBlank { null },
                        artworkUri = currentBook.coverUrl,
                        initialPositionMs = resolvedLocal?.audioPositionMs ?: 0L,
                        playbackSpeed = savedSettings.playbackSpeed,
                        bookId = currentBook.id,
                    )
                    playback.markLoaded(owner, bookId)
                } else if (content.audioFile != null && live == null) {
                    audioController.prepareFile(
                        audioFile = content.audioFile,
                        title = currentBook.title,
                        artist = currentBook.authors.joinToString().ifBlank { null },
                        artworkUri = currentBook.coverUrl,
                        initialPositionMs = resolvedLocal?.audioPositionMs ?: 0L,
                        playbackSpeed = savedSettings.playbackSpeed,
                        bookId = currentBook.id,
                    )
                    playback.markLoaded(owner, bookId)
                }

                stateMachine.openBook(
                    book = currentBook,
                    checkpoint = resolvedLocal,
                    smilNodes = content.smilNodes,
                    preferAudioOnOpen = currentBook.mediaType != BookMediaType.EBOOK_ONLY,
                )
                stateMachine.setPlaybackSpeed(savedSettings.playbackSpeed)
                _readingAnchor.value = (stateMachine.state.value as? ReaderMode.ReadingOnly)?.activeSentenceId

                if (live != null) {
                    // The audio is the freshest position there is; skip the server sync's override.
                    stateMachine.onAudioPositionChanged(live.positionMs)
                    if (live.isPlaying) {
                        stateMachine.onPlay()
                        startPlaybackTicker()
                        promoteToReadingIfNeeded()
                    }
                    startPeriodicSync()
                    _sessionLoaded.value = true
                    return@launch
                }

                val synced = bookRepository.syncBookPosition(bookId).getOrNull()
                val resolvedSynced = resolveCheckpoint(synced, content)
                if (resolvedSynced != null && resolvedSynced != resolvedLocal) {
                    stateMachine.openBook(
                        book = currentBook,
                        checkpoint = resolvedSynced,
                        smilNodes = content.smilNodes,
                        preferAudioOnOpen = currentBook.mediaType != BookMediaType.EBOOK_ONLY,
                    )
                    // Not savedSettings: the user may have changed speed while the sync was in flight.
                    stateMachine.setPlaybackSpeed(preferredSpeed)
                    val syncedAudioMs = when (val mode = stateMachine.state.value) {
                        is ReaderMode.ReadaloudSynced -> mode.audioPositionMs
                        is ReaderMode.ListeningOnly -> mode.audioPositionMs
                        else -> resolvedSynced.audioPositionMs
                    }
                    audioController.seekTo(syncedAudioMs)
                    _readingAnchor.value = (stateMachine.state.value as? ReaderMode.ReadingOnly)?.activeSentenceId
                    if (currentBook.mediaType == BookMediaType.AUDIO_ONLY) {
                        _resumedAudioNotice.value = ResumedAudioNotice(
                            chapterTitle = resolvedSynced.chapterTitle,
                            chapterIndex = resolvedSynced.chapterIndex,
                            positionMs = resolvedSynced.audioPositionMs,
                        )
                    } else {
                        _resumedElementId.value = resolvedSynced.activeSentenceId
                            ?: (stateMachine.state.value as? ReaderMode.ReadingOnly)?.activeSentenceId
                            ?: resolvedSynced.readingCfi
                    }
                }
                startPeriodicSync()
                _sessionLoaded.value = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _sessionError.value = e.message ?: "Failed to open book"
                _sessionLoaded.value = true
            }
        }
    }

    /** Re-runs the open sequence after a failure (e.g. the server was unreachable or the file was missing). */
    fun retryOpen() {
        if (_sessionError.value == null) return
        _sessionError.value = null
        _sessionLoaded.value = false
        openBookSession()
    }

    fun togglePlayPause() {
        when (val mode = stateMachine.state.value) {
            is ReaderMode.ReadaloudSynced -> {
                if (mode.playbackState == SyncedPlaybackState.PLAYING) pause() else play()
            }
            is ReaderMode.ListeningOnly -> {
                if (mode.isPlaying) pause() else play()
            }
            is ReaderMode.ReadingOnly -> {
                if (mode.book.mediaType == BookMediaType.READALOUD) {
                    stateMachine.enableSynchronizedNarration()
                    val posMs = (stateMachine.state.value as? ReaderMode.ReadaloudSynced)?.audioPositionMs ?: 0L
                    audioController.seekTo(posMs)
                    audioController.play()
                    startPlaybackTicker()
                    promoteToReadingIfNeeded()
                }
            }
            else -> Unit
        }
    }

    fun play() {
        // NarrationPlayerService applies smart rewind when playback resumes, so the player is not
        // seeked here as well (that rewound twice); the ticker picks up the rewound position.
        stateMachine.onPlay()
        audioController.play()
        startPlaybackTicker()
        promoteToReadingIfNeeded()
    }

    fun pause() {
        playbackTickerJob?.cancel()
        playbackTickerJob = null
        audioController.pause()
        stateMachine.onPause()
        persistCurrentCheckpoint()
    }

    fun toggleSynchronizedNarration() {
        when (stateMachine.state.value) {
            is ReaderMode.ReadingOnly -> {
                stateMachine.enableSynchronizedNarration()
                stateMachine.setPlaybackSpeed(preferredSpeed)
                val posMs = (stateMachine.state.value as? ReaderMode.ReadaloudSynced)?.audioPositionMs ?: 0L
                audioController.seekTo(posMs)
                audioController.play()
                startPlaybackTicker()
                promoteToReadingIfNeeded()
            }
            is ReaderMode.ReadaloudSynced -> {
                playbackTickerJob?.cancel()
                playbackTickerJob = null
                audioController.pause()
                stateMachine.disableSynchronizedNarration()
                _readingAnchor.value = (stateMachine.state.value as? ReaderMode.ReadingOnly)?.activeSentenceId
                persistCurrentCheckpoint()
            }
            else -> Unit
        }
    }

    fun onSentenceTapped(sentence: ReaderSentenceItem) {
        setFollowingNarration(true)
        val node = stateMachine.onSentenceTapped(sentence.elementId, sentence.textHref)
        if (node != null) {
            stateMachine.setPlaybackSpeed(preferredSpeed)
            audioController.seekTo(node.clipBeginMs)
            audioController.play()
            startPlaybackTicker()
            persistCurrentCheckpoint()
        }
    }

    fun seekToMs(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        audioController.seekTo(clamped)
        stateMachine.onAudioPositionChanged(clamped)
        syncChapter(stateMachine.state.value)
        persistCurrentCheckpoint()
    }

    fun skipByMs(deltaMs: Long) {
        val currentMs = when (val mode = stateMachine.state.value) {
            is ReaderMode.ReadaloudSynced -> mode.audioPositionMs
            is ReaderMode.ListeningOnly -> mode.audioPositionMs
            else -> return
        }
        val totalMs = when (val mode = stateMachine.state.value) {
            is ReaderMode.ReadaloudSynced -> mode.totalDurationMs
            is ReaderMode.ListeningOnly -> mode.totalDurationMs
            else -> Long.MAX_VALUE
        }
        val target = (currentMs + deltaMs).coerceIn(0L, totalMs.coerceAtLeast(0L))
        audioController.seekTo(target)
        stateMachine.onAudioPositionChanged(target)
        persistCurrentCheckpoint()
    }

    fun setPlaybackSpeed(speed: Float) {
        val snapped = ReaderStateMachine.snapSpeed(speed)
        preferredSpeed = snapped
        stateMachine.setPlaybackSpeed(snapped)
        audioController.setSpeed(snapped)
        viewModelScope.launch {
            preferencesRepository.updateSettings { it.copy(playbackSpeed = snapped) }
        }
    }

    fun startSleepTimer(minutes: Int) = playback.startSleepTimer(minutes)

    /** Pauses when narration reaches the end of the current chapter; no-op without chapter timing. */
    fun sleepAtEndOfChapter() {
        val end = currentChapterIndex()?.let { _chapters.value[it]?.endMs } ?: return
        playback.sleepAt(end)
    }

    fun cancelSleepTimer() = playback.cancelSleepTimer()

    /**
     * Navigates the reader viewport/page directly to where audio narration is currently reading,
     * without changing, pausing, or seeking the audio playback.
     */
    fun jumpToActiveNarration() {
        setFollowingNarration(true)
        val elementId = (readerMode.value as? ReaderMode.ReadaloudSynced)?.highlight?.elementId
        if (elementId != null) {
            _scrollRequests.tryEmit(ReaderScrollTarget(elementId = elementId, pauseAutoFollow = false))
        }
    }

    // --- Chapters -----------------------------------------------------------------------------

    /** Narration seeks to the chapter (staying paused if paused); silent reading scrolls to it. */
    fun goToChapter(index: Int) {
        val chapter = _chapters.value[index] ?: return
        val start = chapter.startMs ?: _sentences.value.firstOrNull { it.elementId == chapter.firstElementId }?.clipBeginMs
        setFollowingNarration(true)
        when (stateMachine.state.value) {
            is ReaderMode.ReadaloudSynced, is ReaderMode.ListeningOnly -> {
                if (start != null) seekToMs(start)
                // Turn to the chapter even when the reader had paged away and narration isn't being followed.
                _scrollRequests.tryEmit(ReaderScrollTarget(chapter.firstElementId, pauseAutoFollow = false))
            }
            is ReaderMode.ReadingOnly -> _scrollRequests.tryEmit(ReaderScrollTarget(chapter.firstElementId, pauseAutoFollow = false))
            else -> Unit
        }
    }

    /** Like a CD player: back to the start of this chapter, or to the previous one if already near its start. */
    fun previousChapter() {
        val index = currentChapterIndex() ?: return
        val chapter = _chapters.value[index] ?: return
        val position = audioPositionMs()
        val start = chapter.startMs
        val restart = position != null && start != null && position - start > RESTART_CHAPTER_THRESHOLD_MS
        goToChapter(if (restart || index == 0) index else index - 1)
    }

    fun nextChapter() {
        val index = currentChapterIndex() ?: return
        if (index < _chapters.value.size - 1) goToChapter(index + 1)
    }

    /** First paragraph on screen while reading; saved (debounced) as the reading position. */
    fun onVisibleElementChanged(elementId: String?) {
        if (elementId == null) return
        if (stateMachine.state.value !is ReaderMode.ReadingOnly) return
        val items = _sentences.value
        val index = items.indexOfFirst { it.elementId == elementId }
        if (index < 0) return
        val percent = if (items.size <= 1) 0f else index * 100f / (items.size - 1)
        stateMachine.onReadingPositionChanged(elementId, percent)
        readingSaveJob?.cancel()
        readingSaveJob = viewModelScope.launch {
            delay(READING_SAVE_DEBOUNCE_MS)
            persistCurrentCheckpoint()
        }
    }

    private fun currentChapterIndex(): Int? = when (val mode = stateMachine.state.value) {
        is ReaderMode.ReadaloudSynced -> mode.chapterIndex
        is ReaderMode.ListeningOnly -> mode.chapterIndex
        is ReaderMode.ReadingOnly -> mode.chapterIndex
        else -> null
    }?.takeIf { _chapters.value.size > 0 }?.coerceIn(0, _chapters.value.size - 1)

    private fun audioPositionMs(): Long? = when (val mode = stateMachine.state.value) {
        is ReaderMode.ReadaloudSynced -> mode.audioPositionMs
        is ReaderMode.ListeningOnly -> mode.audioPositionMs
        else -> null
    }

    /** Keeps the mode's chapter in step with the position: narration time first, else the paragraph. */
    private fun syncChapter(mode: ReaderMode) {
        val chapters = _chapters.value
        if (chapters.size == 0) return
        val index = when (mode) {
            is ReaderMode.ReadaloudSynced ->
                chapters.indexAt(mode.audioPositionMs) ?: chapters.indexOfElement(mode.highlight?.elementId)
            is ReaderMode.ListeningOnly -> chapters.indexAt(mode.audioPositionMs)
            is ReaderMode.ReadingOnly -> chapters.indexOfElement(mode.activeSentenceId)
            else -> null
        } ?: return
        stateMachine.onChapterChanged(index, chapters[index]?.title)
    }

    private fun nowPlayingOf(mode: ReaderMode): NowPlaying? {
        if (!audioController.hasLoadedMedia) return null
        return when (mode) {
            is ReaderMode.ReadaloudSynced -> NowPlaying(
                book = mode.book,
                chapterTitle = mode.chapterTitle,
                positionMs = mode.audioPositionMs,
                durationMs = mode.totalDurationMs,
                isPlaying = mode.playbackState == SyncedPlaybackState.PLAYING,
                speed = mode.playbackSpeed,
            )
            is ReaderMode.ListeningOnly -> NowPlaying(
                book = mode.book,
                chapterTitle = mode.chapterTitle,
                positionMs = mode.audioPositionMs,
                durationMs = mode.totalDurationMs,
                isPlaying = mode.isPlaying,
                speed = mode.playbackSpeed,
            )
            else -> null
        }
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            preferencesRepository.updateSettings(transform)
        }
    }

    fun resolveConflict(useRemote: Boolean) {
        viewModelScope.launch {
            val resolved = bookRepository.resolveConflict(bookId, useRemote) ?: return@launch
            val currentBook = when (val mode = stateMachine.state.value) {
                is ReaderMode.ReadaloudSynced -> mode.book
                is ReaderMode.ReadingOnly -> mode.book
                is ReaderMode.ListeningOnly -> mode.book
                else -> return@launch
            }
            stateMachine.openBook(
                book = currentBook,
                checkpoint = resolved,
                smilNodes = loadedSmilNodes,
                preferAudioOnOpen = currentBook.mediaType != BookMediaType.EBOOK_ONLY,
            )
            stateMachine.setPlaybackSpeed(preferredSpeed)
            audioController.seekTo(resolved.audioPositionMs)
            _readingAnchor.value = (stateMachine.state.value as? ReaderMode.ReadingOnly)?.activeSentenceId

            if (useRemote) {
                if (currentBook.mediaType == BookMediaType.AUDIO_ONLY) {
                    _resumedAudioNotice.value = ResumedAudioNotice(
                        chapterTitle = resolved.chapterTitle,
                        chapterIndex = resolved.chapterIndex,
                        positionMs = resolved.audioPositionMs,
                    )
                } else {
                    _resumedElementId.value = resolved.activeSentenceId
                        ?: (stateMachine.state.value as? ReaderMode.ReadingOnly)?.activeSentenceId
                        ?: resolved.readingCfi
                }
            }
        }
    }

    private fun startPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = viewModelScope.launch {
            val tickMs = 200L
            while (isActive) {
                delay(tickMs)
                // Paused from the notification, lock screen or a headset.
                if (audioController.isPlaybackRequested() == false) {
                    stateMachine.onPause()
                    persistCurrentCheckpoint()
                    break
                }
                val hardwarePosMs = audioController.currentPositionMs()
                when (val mode = stateMachine.state.value) {
                    is ReaderMode.ReadaloudSynced -> {
                        if (mode.playbackState != SyncedPlaybackState.PLAYING) break
                        promoteToReadingIfNeeded()
                        val advance = (tickMs * mode.playbackSpeed).toLong()
                        val next = hardwarePosMs ?: (mode.audioPositionMs + advance).coerceAtMost(mode.totalDurationMs)
                        stateMachine.onAudioPositionChanged(next)
                        val currentActiveId = (stateMachine.state.value as? ReaderMode.ReadaloudSynced)?.highlight?.elementId
                        if (_resumedElementId.value != null && currentActiveId != null && currentActiveId != _resumedElementId.value) {
                            clearResumeHighlight()
                        }
                        if (next >= mode.totalDurationMs && mode.totalDurationMs > 0L) {
                            pause()
                            break
                        }
                    }
                    is ReaderMode.ListeningOnly -> {
                        if (!mode.isPlaying) break
                        promoteToReadingIfNeeded()
                        val advance = (tickMs * mode.playbackSpeed).toLong()
                        val next = hardwarePosMs ?: (mode.audioPositionMs + advance).coerceAtMost(mode.totalDurationMs)
                        stateMachine.onAudioPositionChanged(next)
                        if (_resumedAudioNotice.value != null) {
                            clearResumeHighlight()
                        }
                        if (next >= mode.totalDurationMs && mode.totalDurationMs > 0L) {
                            pause()
                            break
                        }
                    }
                    else -> break
                }
            }
        }
    }

    /**
     * Playing narration marks a To read book as Reading right away. Silent reading is left to the
     * server, which promotes the book when the position syncs (the first paragraph reported on
     * screen is not a reliable sign that a page was turned, so it can't tell opening from reading).
     */
    private fun promoteToReadingIfNeeded() {
        if (hasPromotedToReadingThisSession) return
        val book = currentBook() ?: return
        if (!book.isToRead) return
        hasPromotedToReadingThisSession = true

        viewModelScope.launch {
            val statuses = bookRepository.getReadingStatuses()
            val readingStatus = statuses.firstOrNull {
                it.name.equals("reading", ignoreCase = true) || it.uuid.equals("reading", ignoreCase = true)
            } ?: ReadingStatusItem.DEFAULT_STATUSES.first { it.name == "reading" }

            bookRepository.updateBookReadingStatus(book.id, readingStatus)
        }
    }

    private fun persistCurrentCheckpoint() {
        val checkpoint = currentCheckpoint() ?: return
        viewModelScope.launch {
            bookRepository.saveCheckpoint(checkpoint)
        }
    }

    private fun startPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = viewModelScope.launch {
            while (isActive) {
                delay(PERIODIC_SYNC_INTERVAL_MS)
                persistCurrentCheckpoint()
            }
        }
    }

    /**
     * Imported books are saved in the local library's scope whatever server is active
     * (or none); server books in their server's scope.
     */
    private fun currentCheckpoint(): SyncCheckpoint? {
        val book = currentBook() ?: return null
        val base = if (book.isLocal) {
            stateMachine.toSyncCheckpoint(LocalLibrary.SERVER_ID, LocalLibrary.USER_ID)
        } else {
            val profile = vault.activeProfile.value ?: return null
            stateMachine.toSyncCheckpoint(profile.id, profile.userId)
        } ?: return null

        return when (stateMachine.state.value) {
            is ReaderMode.ListeningOnly -> {
                val tracks = loadedAudioTracks
                val currentPos = base.audioPositionMs
                val track = tracks.firstOrNull { currentPos >= it.startOffsetMs && currentPos < it.startOffsetMs + it.durationMs }
                    ?: tracks.firstOrNull()
                val trackOffsetMs = if (track != null) (currentPos - track.startOffsetMs).coerceAtLeast(0L) else currentPos
                val trackSec = trackOffsetMs / 1000.0
                val chapterProg = if (track != null && track.durationMs > 0L) {
                    (trackOffsetMs.toFloat() / track.durationMs.toFloat()).coerceIn(0f, 1f)
                } else null
                base.copy(
                    readingCfi = track?.audioSrc,
                    activeSentenceId = String.format(java.util.Locale.US, "t=%.3f", trackSec),
                    chapterProgression = chapterProg,
                )
            }
            is ReaderMode.ReadingOnly, is ReaderMode.ReadaloudSynced -> {
                val sentence = _sentences.value.firstOrNull { it.elementId == base.activeSentenceId }
                val targetHref = sentence?.textHref ?: base.readingCfi
                val fragment = sentence?.rawHtmlId ?: base.activeSentenceId
                val chapterSentences = if (targetHref != null) _sentences.value.filter { it.textHref == targetHref } else emptyList()
                val sentenceIdx = if (chapterSentences.isNotEmpty() && sentence != null) chapterSentences.indexOf(sentence) else -1
                val chapterProg = if (chapterSentences.isNotEmpty() && sentenceIdx >= 0) {
                    (sentenceIdx.toFloat() / chapterSentences.size.toFloat()).coerceIn(0f, 1f)
                } else null
                base.copy(
                    readingCfi = targetHref,
                    activeSentenceId = fragment,
                    chapterProgression = chapterProg,
                )
            }
            else -> base
        }
    }

    private fun resolveCheckpoint(checkpoint: SyncCheckpoint?, content: LoadedBookContent): SyncCheckpoint? {
        if (checkpoint == null) return null
        val resolvedAudioMs: Long = BookContentLoader.resolveCheckpointAudioPosition(checkpoint, content)
        val resolvedSentenceId = when {
            checkpoint.activeSentenceId?.startsWith("t=") == true -> null
            checkpoint.activeSentenceId != null -> {
                val byHrefAndRaw = if (checkpoint.readingCfi != null) {
                    content.sentences.firstOrNull {
                        it.textHref == checkpoint.readingCfi && it.rawHtmlId != null && it.rawHtmlId == checkpoint.activeSentenceId
                    }
                } else null

                byHrefAndRaw?.elementId
                    ?: content.sentences.firstOrNull {
                        (it.rawHtmlId != null && it.rawHtmlId == checkpoint.activeSentenceId) || it.elementId == checkpoint.activeSentenceId
                    }?.elementId
                    ?: checkpoint.activeSentenceId
            }
            else -> null
        }

        return checkpoint.copy(
            audioPositionMs = resolvedAudioMs,
            activeSentenceId = resolvedSentenceId,
        )
    }

    // --- Selection -------------------------------------------------------------------------

    fun startSelection(sentence: ReaderSentenceItem, offset: Int) {
        val word = ReaderTextLogic.wordRangeAt(sentence.text, offset)
        _selection.value = word?.let { ReaderSelection(sentence, anchor = it, range = it) }
    }

    /** Drag extension stays within the paragraph the selection started in. */
    fun extendSelection(sentence: ReaderSentenceItem, offset: Int) {
        val current = _selection.value ?: return
        if (current.sentence.elementId != sentence.elementId) return
        _selection.value = current.copy(range = ReaderTextLogic.extendSelection(sentence.text, current.anchor, offset))
    }

    fun clearSelection() {
        _selection.value = null
    }

    // --- Highlights, notes, bookmarks ---------------------------------------------------------

    /** Saves the current selection as a highlight (with an optional note) and clears the selection. */
    fun highlightSelection(color: HighlightColor, note: String? = null) {
        val selection = _selection.value ?: return
        _selection.value = null
        val (chapterIndex, chapterTitle) = currentChapter()
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            // Re-highlighting exactly the same span updates it in place (keeping its note and date)
            // rather than stacking a duplicate.
            val existing = annotations.value.firstOrNull { it.isHighlightOf(selection) }
            if (existing != null) {
                annotationRepository.updateAnnotation(existing.id) {
                    it.copy(color = color, note = cleanNote ?: it.note)
                }
                return@launch
            }
            annotationRepository.addHighlight(
                bookId = bookId,
                selectedText = selection.text,
                note = cleanNote,
                color = color,
                textHref = selection.sentence.textHref,
                elementId = selection.sentence.elementId,
                startOffset = selection.range.start,
                endOffset = selection.range.end,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
            )
        }
    }

    /** A blank note removes the note but keeps the highlight. */
    fun editNote(annotation: BookAnnotation, note: String) {
        viewModelScope.launch {
            annotationRepository.updateNote(annotation.id, note.trim().takeIf { it.isNotEmpty() })
        }
    }

    fun deleteAnnotation(annotation: BookAnnotation) {
        viewModelScope.launch { annotationRepository.deleteAnnotation(annotation.id) }
    }

    fun isBookmarked(elementId: String): Boolean =
        annotations.value.any { it.type == AnnotationType.BOOKMARK && it.elementId == elementId }

    fun toggleBookmark(sentence: ReaderSentenceItem) {
        val (chapterIndex, chapterTitle) = currentChapter()
        viewModelScope.launch {
            val existing = annotations.value.filter { it.type == AnnotationType.BOOKMARK && it.elementId == sentence.elementId }
            if (existing.isNotEmpty()) {
                existing.forEach { annotationRepository.deleteAnnotation(it.id) }
            } else {
                annotationRepository.addBookmark(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    textHref = sentence.textHref,
                    elementId = sentence.elementId,
                )
            }
        }
    }

    fun jumpTo(annotation: BookAnnotation) {
        annotation.elementId?.let { _scrollRequests.tryEmit(ReaderScrollTarget(it, pauseAutoFollow = true)) }
    }

    /** Markdown export of every highlight, note and bookmark, or null before the book has loaded. */
    fun exportMarkdown(): String? {
        val book = currentBook() ?: return null
        return annotationRepository.exportToMarkdown(
            bookTitle = book.title,
            bookAuthor = book.authors.joinToString().ifBlank { null },
            annotations = annotations.value,
        )
    }

    // --- Dictionary ---------------------------------------------------------------------------

    /** Looks up only on explicit request; a new lookup replaces any one in flight. */
    fun define(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        _selection.value = null
        lookupJob?.cancel()
        _dictionary.value = DictionaryUiState.Loading(trimmed)
        lookupJob = viewModelScope.launch {
            _dictionary.value = when (val result = lookupWord(trimmed)) {
                is DictionaryResult.Success -> DictionaryUiState.Loaded(trimmed, result.entry)
                is DictionaryResult.NotFound -> DictionaryUiState.NotFound(trimmed)
                is DictionaryResult.NetworkError -> DictionaryUiState.Failed(trimmed, result.message)
            }
        }
    }

    fun dismissDictionary() {
        lookupJob?.cancel()
        _dictionary.value = null
    }

    private fun currentBook(): Book? = when (val mode = stateMachine.state.value) {
        is ReaderMode.ReadaloudSynced -> mode.book
        is ReaderMode.ReadingOnly -> mode.book
        is ReaderMode.ListeningOnly -> mode.book
        is ReaderMode.NotDownloaded -> mode.book
        else -> null
    }

    private fun currentChapter(): Pair<Int, String?> = when (val mode = stateMachine.state.value) {
        is ReaderMode.ReadaloudSynced -> mode.chapterIndex to mode.chapterTitle
        is ReaderMode.ReadingOnly -> mode.chapterIndex to mode.chapterTitle
        is ReaderMode.ListeningOnly -> mode.chapterIndex to mode.chapterTitle
        else -> 0 to null
    }

    private fun BookAnnotation.isHighlightOf(selection: ReaderSelection): Boolean =
        type == AnnotationType.HIGHLIGHT &&
            elementId == selection.sentence.elementId &&
            startOffset == selection.range.start &&
            endOffset == selection.range.end

    override fun onCleared() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
        // viewModelScope is already cancelled here; capture now and write on the outliving scope.
        val checkpoint = currentCheckpoint()
        if (checkpoint != null) {
            persistenceScope.launch { bookRepository.saveCheckpoint(checkpoint) }
        }
        // Narration that is still playing carries on in the background (mini player).
        val book = currentBook()
        playback.detach(
            owner,
            if (book != null && checkpoint != null) {
                BackgroundHandoff(book, checkpoint, loadedSmilNodes, _chapters.value, preferredSpeed)
            } else {
                null
            },
        )
        super.onCleared()
    }

    companion object {
        /** "Previous chapter" restarts the current one when more than this far into it. */
        private const val RESTART_CHAPTER_THRESHOLD_MS = 3_000L
        private const val READING_SAVE_DEBOUNCE_MS = 2_000L
        private const val PERIODIC_SYNC_INTERVAL_MS = 30_000L

        fun factory(bookId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as HelloApplication
                val container = app.container
                return ReaderViewModel(
                    bookId = bookId,
                    bookRepository = container.bookRepository,
                    preferencesRepository = container.readerPreferencesRepository,
                    vault = container.vault,
                    annotationRepository = container.annotationRepository,
                    lookupWord = container.dictionaryService::lookup,
                    playback = container.playbackSession,
                    persistenceScope = container.applicationScope,
                    downloadScheduler = container.downloadScheduler,
                ) as T
            }
        }
    }
}
