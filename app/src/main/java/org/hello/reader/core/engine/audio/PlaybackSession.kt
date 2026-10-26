package org.hello.reader.core.engine.audio

import org.hello.reader.core.engine.smil.AudioTrackItem
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.engine.smil.SmilParNode
import org.hello.reader.core.engine.smil.SmilSyncEngine
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.SyncCheckpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the mini player shows. */
data class NowPlaying(
    val book: Book,
    val chapterTitle: String?,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val speed: Float,
) {
    /** 0..1 */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Position of a book whose audio was still loaded when the reader reopened it. */
data class LivePlayback(val positionMs: Long, val isPlaying: Boolean)

/** Everything needed to keep a book playing, and its position saved, once the reader has closed. */
data class BackgroundHandoff(
    val book: Book,
    /** The reader's last checkpoint, already in the right server/user scope. */
    val checkpoint: SyncCheckpoint,
    val smilNodes: List<SmilParNode>,
    val chapters: BookChapters,
    val speed: Float,
)

/** The reader that is currently driving playback. */
interface PlaybackOwner {
    /** The sleep timer ran out while this reader was open. */
    fun pauseFromSession()
    fun togglePlayPause() {}
    fun previousChapter() {}
    fun nextChapter() {}
    fun skipBy(deltaMs: Long) {}
}

/**
 * App-lifetime owner of narration playback, so audio outlives the reader screen.
 *
 * While a reader is open it drives [controller] itself and [publish]es what is playing. When it
 * closes mid-playback it [detach]es with a [BackgroundHandoff]; the session then follows the
 * player, keeps [nowPlaying] current for the mini player, takes the mini player's commands and
 * saves the position every [SAVE_INTERVAL_MS] and on every pause. Reopening that book [attach]es
 * to the live audio instead of reloading it; opening another book stops it.
 *
 * The sleep timer lives here too, so it keeps counting after the reader closes.
 * Call everything from the main thread (Media3 controllers are main-thread only).
 */
class PlaybackSession(
    controllerFactory: () -> AudioPlaybackController,
    private val saveCheckpoint: suspend (SyncCheckpoint) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val controller: AudioPlaybackController by lazy(controllerFactory)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var owner: PlaybackOwner? = null
    private var loadedBookId: String? = null
    private var background: Background? = null
    private var tickerJob: Job? = null
    private var lastHandoffTemplate: BackgroundHandoff? = null

    /**
     * Starts playback from an external client (Android Auto, media browser, widget) with no active reader.
     * Prepares the audio tracks, seeks to [startMs], starts playback and enters background tracking.
     */
    fun startFromLibrary(
        handoff: BackgroundHandoff,
        tracks: List<AudioTrackItem>,
        startMs: Long,
        artworkUri: String? = null,
    ) {
        owner?.pauseFromSession()
        // The player still holds the previous book here, so its live position is accurate.
        saveReplacedBackground(handoff.book.id, refreshFromPlayer = true)
        owner = null
        loadedBookId = handoff.book.id
        lastHandoffTemplate = handoff
        controller.prepareTracks(
            tracks = tracks,
            title = handoff.book.title,
            artist = handoff.book.authors.joinToString().ifBlank { null },
            artworkUri = artworkUri,
            initialPositionMs = startMs,
            playbackSpeed = handoff.speed,
            bookId = handoff.book.id,
        )
        controller.seekTo(startMs)
        controller.play()
        startBackground(handoff.copy(checkpoint = handoff.checkpoint.copy(audioPositionMs = startMs)))
    }

    /**
     * Adopts playback when Media3 has already set media items directly on ExoPlayer (e.g. from
     * [NarrationPlayerService.onSetMediaItems] or [NarrationPlayerService.onPlaybackResumption]).
     * Pauses any active reader, adopts the tracks in the controller, and starts background tracking
     * without calling prepareTracks again.
     */
    fun adoptBackgroundPlayback(
        handoff: BackgroundHandoff,
        tracks: List<AudioTrackItem>,
        startMs: Long,
    ) {
        owner?.pauseFromSession()
        // Media3 may already hold the new items, so keep the last ticked position for the old book.
        saveReplacedBackground(handoff.book.id, refreshFromPlayer = false)
        owner = null
        loadedBookId = handoff.book.id
        lastHandoffTemplate = handoff
        controller.adoptTracks(tracks)
        controller.setSpeed(handoff.speed)
        startBackground(
            handoff.copy(checkpoint = handoff.checkpoint.copy(audioPositionMs = startMs)),
            startPositionMs = startMs,
            awaitMediaSwitch = true,
        )
    }

    /** Saves the background book being replaced by [newBookId], so its last seconds aren't lost. */
    private fun saveReplacedBackground(newBookId: String, refreshFromPlayer: Boolean) {
        val previous = background ?: return
        if (previous.handoff.book.id == newBookId) return
        if (refreshFromPlayer) previous.positionMs = livePositionMs(previous) ?: previous.positionMs
        save(previous)
    }

    private val sleepTimer = SleepTimerManager(
        setPlayerVolume = { controller.setVolume(it) },
        onTimerExpired = ::pauseForSleep,
        scope = scope,
    )

    /** Countdown of a minutes-based sleep timer, or null. */
    val sleepTimerRemainingMs: StateFlow<Long?> = sleepTimer.remainingMs

    private val _sleepAtMs = MutableStateFlow<Long?>(null)

    /** Audio position at which playback pauses for "end of chapter", or null. */
    val sleepAtMs: StateFlow<Long?> = _sleepAtMs.asStateFlow()

    private class Background(
        val handoff: BackgroundHandoff,
        val engine: SmilSyncEngine,
        var positionMs: Long,
        var wasPlaying: Boolean,
        var lastSavedAt: Long,
    ) {
        /** When the switch to this book's media began, or null once the player is on it. */
        var awaitingMediaSwitchSince: Long? = null
    }

    // --- Reader side ----------------------------------------------------------------------------

    /**
     * A reader opened [bookId] and takes over playback. Returns the live position when that book's
     * audio is still loaded (the reader must not reload it); otherwise stops whatever else was playing.
     */
    fun attach(bookId: String, newOwner: PlaybackOwner): LivePlayback? {
        owner = newOwner
        val previous = background
        stopTicker()
        background = null
        if (loadedBookId == bookId && controller.hasLoadedMedia) {
            return LivePlayback(
                positionMs = livePositionMs(previous) ?: previous?.positionMs ?: 0L,
                isPlaying = controller.isPlaybackRequested() ?: previous?.wasPlaying ?: false,
            )
        }
        if (previous != null) save(previous)
        if (loadedBookId != null) {
            controller.stop()
            cancelSleepTimer()
        }
        loadedBookId = null
        _nowPlaying.value = null
        lastHandoffTemplate = null
        return null
    }

    /** The reader loaded [bookId]'s audio into [controller]. */
    fun markLoaded(owner: PlaybackOwner, bookId: String) {
        if (owner === this.owner) loadedBookId = bookId
    }

    /** The open reader's current playback, or null when it has none. */
    fun publish(owner: PlaybackOwner, nowPlaying: NowPlaying?) {
        if (owner !== this.owner) return
        _nowPlaying.value = nowPlaying
        nowPlaying?.let { checkSleepAt(it.positionMs, it.isPlaying) }
    }

    /**
     * The reader closed. With a [handoff] and audio still playing, playback continues in the
     * background; otherwise it stops. Ignored if another reader has attached since.
     */
    fun detach(owner: PlaybackOwner, handoff: BackgroundHandoff?) {
        if (owner !== this.owner) return
        this.owner = null
        if (handoff != null) lastHandoffTemplate = handoff
        val isLoaded = loadedBookId != null && controller.hasLoadedMedia
        if (handoff != null && isLoaded && loadedBookId == handoff.book.id) {
            startBackground(handoff)
        } else {
            stopPlayback()
        }
    }

    /**
     * Synchronously snapshots the current checkpoint (whether actively playing in [background] or
     * paused with [lastHandoffTemplate]), stops playback without launching a duplicate background
     * save, and returns the checkpoint so the caller can persist it after releasing the player.
     */
    fun snapshotAndStop(): SyncCheckpoint? {
        val bg = background
        val handoff = bg?.handoff ?: lastHandoffTemplate
        val cp = if (handoff != null) {
            val position = livePositionMs(bg) ?: bg?.positionMs ?: handoff.checkpoint.audioPositionMs
            val engine = bg?.engine ?: SmilSyncEngine(handoff.smilNodes)
            val template = handoff.checkpoint
            val total = template.totalDurationMs
            val chapterIndex = handoff.chapters.indexAt(position)
            template.copy(
                audioPositionMs = position,
                progressPercent = if (total > 0L) (position * 100f / total).coerceIn(0f, 100f) else template.progressPercent,
                activeSentenceId = engine.lookupSmilNode(position)?.fragmentId ?: template.activeSentenceId,
                chapterIndex = chapterIndex ?: template.chapterIndex,
                chapterTitle = chapterIndex?.let { handoff.chapters[it]?.title } ?: template.chapterTitle,
                updatedAt = clock(),
                isDirty = true,
            )
        } else {
            null
        }
        stopTicker()
        background = null
        controller.stop()
        loadedBookId = null
        _nowPlaying.value = null
        cancelSleepTimer()
        return cp
    }

    suspend fun persistCheckpoint(checkpoint: SyncCheckpoint, timeoutMs: Long = 1500L) {
        withTimeoutOrNull(timeoutMs) {
            runCatching { saveCheckpoint(checkpoint) }
        }
    }

    /**
     * Persists the current background position immediately (suspending until saveCheckpoint finishes
     * or timeout) and stops playback. Used when the task is removed from Recents.
     */
    suspend fun persistAndStop(timeoutMs: Long = 1500L) {
        val cp = snapshotAndStop()
        if (cp != null) {
            persistCheckpoint(cp, timeoutMs)
        }
    }

    // --- Mini player ----------------------------------------------------------------------------

    fun hasActivePlayback(): Boolean = (owner != null || background != null)

    fun togglePlayPause() {
        val current = owner
        if (current != null) {
            current.togglePlayPause()
            return
        }
        val bg = background ?: return
        if (controller.isPlaybackRequested() == true) controller.pause() else controller.play()
        tick(bg)
    }

    fun skipBy(deltaMs: Long) {
        val current = owner
        if (current != null) {
            current.skipBy(deltaMs)
            return
        }
        val bg = background
        if (bg != null) {
            val total = bg.handoff.checkpoint.totalDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
            val target = (bg.positionMs + deltaMs).coerceIn(0L, total)
            controller.seekTo(target)
            bg.positionMs = target
            tick(bg)
            return
        }
        val pos = controller.currentPositionMs() ?: 0L
        controller.seekTo((pos + deltaMs).coerceAtLeast(0L))
    }

    fun skipToPreviousChapter() {
        val current = owner
        if (current != null) {
            current.previousChapter()
            return
        }
        val bg = background
        if (bg != null && bg.handoff.chapters.hasTiming) {
            val currentIndex = bg.handoff.chapters.indexAt(bg.positionMs) ?: 0
            val currentChapter = bg.handoff.chapters[currentIndex]
            val startMs = currentChapter?.startMs ?: 0L
            val restart = bg.positionMs - startMs > 3_000L || currentIndex == 0
            val targetChapter = if (restart) currentChapter else bg.handoff.chapters[currentIndex - 1]
            val targetMs = targetChapter?.startMs ?: 0L
            controller.seekTo(targetMs)
            bg.positionMs = targetMs
            tick(bg)
            return
        }
        val pos = controller.currentPositionMs() ?: 0L
        controller.seekTo((pos - 30_000L).coerceAtLeast(0L))
    }

    fun skipToNextChapter() {
        val current = owner
        if (current != null) {
            current.nextChapter()
            return
        }
        val bg = background
        if (bg != null && bg.handoff.chapters.hasTiming) {
            val currentIndex = bg.handoff.chapters.indexAt(bg.positionMs) ?: 0
            val nextChapter = bg.handoff.chapters[currentIndex + 1]
            if (nextChapter != null && nextChapter.startMs != null) {
                val targetMs = nextChapter.startMs
                controller.seekTo(targetMs)
                bg.positionMs = targetMs
                tick(bg)
                return
            }
        }
        val pos = controller.currentPositionMs() ?: 0L
        controller.seekTo(pos + 30_000L)
    }

    /** Stops background playback (the mini player's close button). */
    fun stop() {
        if (background == null) return
        stopPlayback()
    }

    // --- Sleep timer ----------------------------------------------------------------------------

    fun startSleepTimer(minutes: Int) {
        _sleepAtMs.value = null
        if (minutes <= 0) sleepTimer.cancel() else sleepTimer.startTimer(minutes * 60_000L)
    }

    /** Pauses once narration reaches [positionMs], the end of the current chapter. */
    fun sleepAt(positionMs: Long) {
        sleepTimer.cancel()
        _sleepAtMs.value = positionMs
    }

    fun cancelSleepTimer() {
        sleepTimer.cancel()
        _sleepAtMs.value = null
    }

    // --- Internals ------------------------------------------------------------------------------

    /**
     * With [awaitMediaSwitch], Media3 hasn't swapped in the new book's items yet, so the player
     * still reports the previous book until [tick] sees one of this book's media IDs.
     */
    private fun startBackground(
        handoff: BackgroundHandoff,
        startPositionMs: Long? = null,
        awaitMediaSwitch: Boolean = false,
    ) {
        stopTicker()
        val bg = Background(
            handoff = handoff,
            engine = SmilSyncEngine(handoff.smilNodes),
            positionMs = startPositionMs ?: controller.currentPositionMs() ?: handoff.checkpoint.audioPositionMs,
            wasPlaying = controller.isPlaybackRequested() ?: false,
            lastSavedAt = clock(),
        )
        if (awaitMediaSwitch) bg.awaitingMediaSwitchSince = clock()
        background = bg
        tick(bg)
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(bg)
            }
        }
    }

    /** Follows the player (which the notification and headset can also control). */
    private fun tick(bg: Background) {
        if (background !== bg) return
        val switchSince = bg.awaitingMediaSwitchSince
        if (switchSince != null) {
            val switched = controller.currentMediaId()?.startsWith(mediaIdPrefix(bg.handoff.book.id)) == true
            if (!switched && clock() - switchSince < MEDIA_SWITCH_TIMEOUT_MS) {
                // The player still reports the previous book: show the new one, but don't read or save.
                publishNowPlaying(bg, bg.wasPlaying)
                return
            }
            bg.awaitingMediaSwitchSince = null
        }
        bg.positionMs = controller.currentPositionMs() ?: bg.positionMs
        var playing = controller.isPlaybackRequested() ?: false
        val total = bg.handoff.checkpoint.totalDurationMs
        if (playing && total > 0L && bg.positionMs >= total) {
            controller.pause()
            playing = false
        }
        publishNowPlaying(bg, playing)
        val now = clock()
        if (playing != bg.wasPlaying || (playing && now - bg.lastSavedAt >= SAVE_INTERVAL_MS)) {
            bg.lastSavedAt = now
            save(bg)
        }
        bg.wasPlaying = playing
        checkSleepAt(bg.positionMs, playing)
    }

    /** The player's position, unless it still holds the book [bg] is replacing. */
    private fun livePositionMs(bg: Background?): Long? =
        if (bg?.awaitingMediaSwitchSince != null) null else controller.currentPositionMs()

    private fun publishNowPlaying(bg: Background, playing: Boolean) {
        val total = bg.handoff.checkpoint.totalDurationMs
        val chapter = bg.handoff.chapters.indexAt(bg.positionMs)?.let(bg.handoff.chapters::get)
        _nowPlaying.value = NowPlaying(
            book = bg.handoff.book,
            chapterTitle = chapter?.title ?: bg.handoff.checkpoint.chapterTitle,
            positionMs = bg.positionMs,
            durationMs = total,
            isPlaying = playing,
            speed = bg.handoff.speed,
        )
    }

    private fun checkSleepAt(positionMs: Long, playing: Boolean) {
        val at = _sleepAtMs.value ?: return
        if (playing && positionMs >= at) {
            _sleepAtMs.value = null
            pauseForSleep()
        }
    }

    private fun pauseForSleep() {
        val current = owner
        if (current != null) {
            current.pauseFromSession()
        } else {
            controller.pause()
            background?.let(::tick)
        }
    }

    private fun checkpointOf(bg: Background): SyncCheckpoint {
        val template = bg.handoff.checkpoint
        val position = bg.positionMs
        val total = template.totalDurationMs
        val chapterIndex = bg.handoff.chapters.indexAt(position)
        return template.copy(
            audioPositionMs = position,
            progressPercent = if (total > 0L) (position * 100f / total).coerceIn(0f, 100f) else template.progressPercent,
            activeSentenceId = bg.engine.lookupSmilNode(position)?.fragmentId ?: template.activeSentenceId,
            chapterIndex = chapterIndex ?: template.chapterIndex,
            chapterTitle = chapterIndex?.let { bg.handoff.chapters[it]?.title } ?: template.chapterTitle,
            updatedAt = clock(),
            isDirty = true,
        )
    }

    private fun save(bg: Background) {
        val checkpoint = checkpointOf(bg)
        scope.launch { runCatching { saveCheckpoint(checkpoint) } }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun stopPlayback() {
        background?.let { bg ->
            bg.positionMs = livePositionMs(bg) ?: bg.positionMs
            save(bg)
        }
        stopTicker()
        background = null
        controller.stop()
        loadedBookId = null
        _nowPlaying.value = null
        cancelSleepTimer()
    }

    companion object {
        const val TICK_MS = 500L
        const val SAVE_INTERVAL_MS = 15_000L
        const val MEDIA_SWITCH_TIMEOUT_MS = 5_000L

        /** Prefix of the media IDs of [bookId]'s tracks, as set by [LibraryPlaybackStarter]. */
        fun mediaIdPrefix(bookId: String): String = "track_${bookId}_"
    }
}
