package org.hello.reader.core.engine.reader

import org.hello.reader.core.engine.audio.SmartRewindPolicy
import org.hello.reader.core.engine.smil.HighlightEvent
import org.hello.reader.core.engine.smil.SmilParNode
import org.hello.reader.core.engine.smil.SmilSyncEngine
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.SyncCheckpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * State machine governing the hybrid Readium EPUB 3 + Media3 narration engine
 * (`ARCHITECTURE.md` Section 6.1).
 *
 * Supports all 3 user-approved media modes:
 * - [BookMediaType.READALOUD]: Instant zero-lag switching between [ReaderMode.ReadingOnly]
 *   and [ReaderMode.ReadaloudSynced] (Whispersync-style), active sentence highlighting,
 *   and Tap-to-Speak.
 * - [BookMediaType.EBOOK_ONLY]: [ReaderMode.ReadingOnly].
 * - [BookMediaType.AUDIO_ONLY]: [ReaderMode.ListeningOnly].
 */
sealed interface ReaderMode {
    data object Idle : ReaderMode
    data class Loading(val bookId: String) : ReaderMode
    data class NotDownloaded(val book: Book) : ReaderMode

    data class ReadingOnly(
        val book: Book,
        val chapterIndex: Int,
        val chapterTitle: String?,
        val readingCfi: String?,
        val activeSentenceId: String?,
        val progressPercent: Float,
    ) : ReaderMode

    data class ListeningOnly(
        val book: Book,
        val chapterIndex: Int,
        val chapterTitle: String?,
        val audioPositionMs: Long,
        val totalDurationMs: Long,
        val isPlaying: Boolean,
        val playbackSpeed: Float,
        val progressPercent: Float,
    ) : ReaderMode

    data class ReadaloudSynced(
        val book: Book,
        val playbackState: SyncedPlaybackState,
        val chapterIndex: Int,
        val chapterTitle: String?,
        val readingCfi: String?,
        val audioPositionMs: Long,
        val totalDurationMs: Long,
        val playbackSpeed: Float,
        val highlight: HighlightEvent?,
        val progressPercent: Float,
    ) : ReaderMode
}

enum class SyncedPlaybackState {
    BUFFERING,
    PLAYING,
    PAUSED,
    SEEKING_TO_SENTENCE,
    CHAPTER_TRANSITION,
}

class ReaderStateMachine(
    private val smilSyncEngine: SmilSyncEngine = SmilSyncEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ReaderMode>(ReaderMode.Idle)
    val state: StateFlow<ReaderMode> = _state.asStateFlow()

    private var pausedAtMs: Long? = null
    private var currentPlaybackSpeed: Float = DEFAULT_SPEED

    fun setNotDownloaded(book: Book) {
        _state.value = ReaderMode.NotDownloaded(book)
    }

    fun openBook(
        book: Book,
        checkpoint: SyncCheckpoint? = null,
        smilNodes: List<SmilParNode> = emptyList(),
        preferAudioOnOpen: Boolean = book.mediaType != BookMediaType.EBOOK_ONLY,
    ) {
        _state.value = ReaderMode.Loading(book.id)
        smilSyncEngine.loadNodes(smilNodes)
        pausedAtMs = null

        val chapterIdx = checkpoint?.chapterIndex ?: 0
        val chapterTitle = checkpoint?.chapterTitle
        val cfi = checkpoint?.readingCfi
        val rawPosMs = checkpoint?.audioPositionMs ?: 0L
        val totalMs = checkpoint?.totalDurationMs?.takeIf { it > 0L }
            ?: (book.durationSeconds * 1_000L).takeIf { it > 0L }
            ?: smilNodes.maxOfOrNull { it.clipEndMs }?.takeIf { it > 0L }
            ?: 0L
        val sentenceId = checkpoint?.activeSentenceId
        val posMs = when {
            rawPosMs > 0L -> rawPosMs
            sentenceId != null -> smilSyncEngine.lookupTimeRange(sentenceId, cfi)?.clipBeginMs ?: 0L
            checkpoint?.progressPercent != null && totalMs > 0L -> ((checkpoint.progressPercent / 100f) * totalMs).toLong()
            else -> 0L
        }
        val progress = checkpoint?.progressPercent ?: computePercent(posMs, totalMs)
        val resolvedSentenceId = sentenceId ?: if (posMs > 0L) smilSyncEngine.lookupSmilNode(posMs)?.fragmentId else null

        _state.value = when (book.mediaType) {
            BookMediaType.EBOOK_ONLY -> ReaderMode.ReadingOnly(
                book = book,
                chapterIndex = chapterIdx,
                chapterTitle = chapterTitle,
                readingCfi = cfi,
                activeSentenceId = resolvedSentenceId ?: sentenceId,
                progressPercent = progress,
            )
            BookMediaType.AUDIO_ONLY -> ReaderMode.ListeningOnly(
                book = book,
                chapterIndex = chapterIdx,
                chapterTitle = chapterTitle,
                audioPositionMs = posMs,
                totalDurationMs = totalMs,
                isPlaying = false,
                playbackSpeed = currentPlaybackSpeed,
                progressPercent = progress,
            )
            BookMediaType.READALOUD -> if (preferAudioOnOpen) {
                val highlight = smilSyncEngine.onPlaybackPositionChanged(posMs)
                ReaderMode.ReadaloudSynced(
                    book = book,
                    playbackState = SyncedPlaybackState.PAUSED,
                    chapterIndex = chapterIdx,
                    chapterTitle = chapterTitle,
                    readingCfi = cfi,
                    audioPositionMs = posMs,
                    totalDurationMs = totalMs,
                    playbackSpeed = currentPlaybackSpeed,
                    highlight = highlight,
                    progressPercent = progress,
                )
            } else {
                ReaderMode.ReadingOnly(
                    book = book,
                    chapterIndex = chapterIdx,
                    chapterTitle = chapterTitle,
                    readingCfi = cfi,
                    activeSentenceId = resolvedSentenceId ?: sentenceId,
                    progressPercent = progress,
                )
            }
        }
    }

    /**
     * Switches a `READALOUD` book from [ReaderMode.ReadingOnly] into [ReaderMode.ReadaloudSynced]
     * seamlessly by resolving the active sentence's SMIL timestamp.
     */
    fun enableSynchronizedNarration() {
        val current = _state.value as? ReaderMode.ReadingOnly ?: return
        if (current.book.mediaType != BookMediaType.READALOUD) return

        val node = current.activeSentenceId?.let { smilSyncEngine.lookupTimeRange(it) }
        val targetPositionMs = node?.clipBeginMs ?: 0L
        val totalMs = (current.book.durationSeconds * 1_000L).takeIf { it > 0L }
            ?: smilSyncEngine.maxClipEndMs()
            ?: 0L
        val highlight = smilSyncEngine.onPlaybackPositionChanged(targetPositionMs, node?.audioSrc)

        _state.value = ReaderMode.ReadaloudSynced(
            book = current.book,
            playbackState = SyncedPlaybackState.PLAYING,
            chapterIndex = current.chapterIndex,
            chapterTitle = current.chapterTitle,
            readingCfi = current.readingCfi,
            audioPositionMs = targetPositionMs,
            totalDurationMs = totalMs,
            playbackSpeed = currentPlaybackSpeed,
            highlight = highlight,
            progressPercent = current.progressPercent,
        )
    }

    /**
     * Switches from [ReaderMode.ReadaloudSynced] back to [ReaderMode.ReadingOnly]
     * while retaining the exact CFI and active sentence ID.
     */
    fun disableSynchronizedNarration() {
        val current = _state.value as? ReaderMode.ReadaloudSynced ?: return
        pausedAtMs = clock()
        _state.value = ReaderMode.ReadingOnly(
            book = current.book,
            chapterIndex = current.chapterIndex,
            chapterTitle = current.chapterTitle,
            readingCfi = current.readingCfi,
            activeSentenceId = current.highlight?.elementId,
            progressPercent = current.progressPercent,
        )
    }

    fun onPlay() {
        val now = clock()
        val pauseElapsed = pausedAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        pausedAtMs = null

        when (val current = _state.value) {
            is ReaderMode.ReadaloudSynced -> {
                val rewoundPos = SmartRewindPolicy.applySmartRewind(current.audioPositionMs, pauseElapsed)
                val highlight = smilSyncEngine.onPlaybackPositionChanged(rewoundPos)
                _state.value = current.copy(
                    playbackState = SyncedPlaybackState.PLAYING,
                    audioPositionMs = rewoundPos,
                    highlight = highlight,
                    progressPercent = computePercent(rewoundPos, current.totalDurationMs),
                )
            }
            is ReaderMode.ListeningOnly -> {
                val rewoundPos = SmartRewindPolicy.applySmartRewind(current.audioPositionMs, pauseElapsed)
                _state.value = current.copy(
                    isPlaying = true,
                    audioPositionMs = rewoundPos,
                    progressPercent = computePercent(rewoundPos, current.totalDurationMs),
                )
            }
            else -> Unit
        }
    }

    fun onPause() {
        pausedAtMs = clock()
        when (val current = _state.value) {
            is ReaderMode.ReadaloudSynced ->
                _state.value = current.copy(playbackState = SyncedPlaybackState.PAUSED)
            is ReaderMode.ListeningOnly ->
                _state.value = current.copy(isPlaying = false)
            else -> Unit
        }
    }

    fun onAudioPositionChanged(positionMs: Long, audioSrc: String? = null) {
        when (val current = _state.value) {
            is ReaderMode.ReadaloudSynced -> {
                val highlight = smilSyncEngine.onPlaybackPositionChanged(positionMs, audioSrc)
                _state.value = current.copy(
                    playbackState = current.playbackState,
                    audioPositionMs = positionMs,
                    highlight = highlight,
                    progressPercent = computePercent(positionMs, current.totalDurationMs),
                )
            }
            is ReaderMode.ListeningOnly -> {
                _state.value = current.copy(
                    audioPositionMs = positionMs,
                    progressPercent = computePercent(positionMs, current.totalDurationMs),
                )
            }
            else -> Unit
        }
    }

    /**
     * Silent reading moved: [elementId] is the first paragraph on screen. Saved in checkpoints and
     * used as the starting point when narration is switched on.
     */
    fun onReadingPositionChanged(elementId: String, progressPercent: Float) {
        val current = _state.value as? ReaderMode.ReadingOnly ?: return
        val progress = progressPercent.coerceIn(0f, 100f)
        if (current.activeSentenceId == elementId && current.progressPercent == progress) return
        _state.value = current.copy(activeSentenceId = elementId, progressPercent = progress)
    }

    /** Records the chapter being read or narrated, as worked out from the book's table of contents. */
    fun onChapterChanged(chapterIndex: Int, chapterTitle: String?) {
        _state.value = when (val current = _state.value) {
            is ReaderMode.ReadingOnly ->
                if (current.chapterIndex == chapterIndex && current.chapterTitle == chapterTitle) return
                else current.copy(chapterIndex = chapterIndex, chapterTitle = chapterTitle)
            is ReaderMode.ListeningOnly ->
                if (current.chapterIndex == chapterIndex && current.chapterTitle == chapterTitle) return
                else current.copy(chapterIndex = chapterIndex, chapterTitle = chapterTitle)
            is ReaderMode.ReadaloudSynced ->
                if (current.chapterIndex == chapterIndex && current.chapterTitle == chapterTitle) return
                else current.copy(chapterIndex = chapterIndex, chapterTitle = chapterTitle)
            else -> return
        }
    }

    /**
     * Tap-to-Speak: User tapped a sentence/paragraph in the Readium navigator.
     * Immediately seeks the audio position to the matching SMIL `clipBeginMs`.
     */
    fun onSentenceTapped(elementId: String, textHref: String? = null): SmilParNode? {
        val node = smilSyncEngine.lookupTimeRange(elementId, textHref) ?: return null
        val highlight = smilSyncEngine.onPlaybackPositionChanged(node.clipBeginMs, node.audioSrc)

        when (val current = _state.value) {
            is ReaderMode.ReadaloudSynced -> {
                _state.value = current.copy(
                    playbackState = SyncedPlaybackState.PLAYING,
                    audioPositionMs = node.clipBeginMs,
                    highlight = highlight,
                    progressPercent = computePercent(node.clipBeginMs, current.totalDurationMs),
                )
            }
            is ReaderMode.ReadingOnly -> {
                if (current.book.mediaType == BookMediaType.READALOUD) {
                    val totalMs = (current.book.durationSeconds * 1_000L).takeIf { it > 0L }
            ?: smilSyncEngine.maxClipEndMs()
            ?: 0L
                    _state.value = ReaderMode.ReadaloudSynced(
                        book = current.book,
                        playbackState = SyncedPlaybackState.PLAYING,
                        chapterIndex = current.chapterIndex,
                        chapterTitle = current.chapterTitle,
                        readingCfi = current.readingCfi,
                        audioPositionMs = node.clipBeginMs,
                        totalDurationMs = totalMs,
                        playbackSpeed = currentPlaybackSpeed,
                        highlight = highlight,
                        progressPercent = computePercent(node.clipBeginMs, totalMs),
                    )
                }
            }
            else -> Unit
        }
        return node
    }

    /**
     * Updates playback speed within `0.5x`..`3.5x` snapped to `0.05x` increments.
     */
    fun setPlaybackSpeed(speed: Float) {
        val clamped = snapSpeed(speed)
        currentPlaybackSpeed = clamped
        when (val current = _state.value) {
            is ReaderMode.ReadaloudSynced -> _state.value = current.copy(playbackSpeed = clamped)
            is ReaderMode.ListeningOnly -> _state.value = current.copy(playbackSpeed = clamped)
            else -> Unit
        }
    }

    fun toSyncCheckpoint(serverId: String, userId: String): SyncCheckpoint? {
        return when (val current = _state.value) {
            is ReaderMode.ReadaloudSynced -> {
                val sentenceId = current.highlight?.elementId
                SyncCheckpoint(
                    bookId = current.book.id,
                    serverId = serverId,
                    userId = userId,
                    readingCfi = current.readingCfi ?: current.highlight?.textHref,
                    chapterIndex = current.chapterIndex,
                    chapterTitle = current.chapterTitle,
                    audioPositionMs = current.audioPositionMs,
                    totalDurationMs = current.totalDurationMs,
                    progressPercent = current.progressPercent,
                    activeSentenceId = sentenceId,
                    updatedAt = clock(),
                    isDirty = true,
                )
            }
            is ReaderMode.ListeningOnly -> SyncCheckpoint(
                bookId = current.book.id,
                serverId = serverId,
                userId = userId,
                readingCfi = null,
                chapterIndex = current.chapterIndex,
                chapterTitle = current.chapterTitle,
                audioPositionMs = current.audioPositionMs,
                totalDurationMs = current.totalDurationMs,
                progressPercent = current.progressPercent,
                activeSentenceId = null,
                updatedAt = clock(),
                isDirty = true,
            )
            is ReaderMode.ReadingOnly -> {
                val sentenceId = current.activeSentenceId
                val smilNode = sentenceId?.let { smilSyncEngine.lookupTimeRange(it, current.readingCfi) }
                val resolvedAudioMs = smilNode?.clipBeginMs ?: 0L
                val totalMs = (current.book.durationSeconds * 1_000L).takeIf { it > 0L }
            ?: smilSyncEngine.maxClipEndMs()
            ?: 0L
                val resolvedProgress = if (current.progressPercent > 0f) {
                    current.progressPercent
                } else if (totalMs > 0L && resolvedAudioMs > 0L) {
                    computePercent(resolvedAudioMs, totalMs)
                } else {
                    0f
                }
                SyncCheckpoint(
                    bookId = current.book.id,
                    serverId = serverId,
                    userId = userId,
                    readingCfi = current.readingCfi ?: smilNode?.textHref,
                    chapterIndex = current.chapterIndex,
                    chapterTitle = current.chapterTitle,
                    audioPositionMs = resolvedAudioMs,
                    totalDurationMs = totalMs,
                    progressPercent = resolvedProgress,
                    activeSentenceId = sentenceId,
                    updatedAt = clock(),
                    isDirty = true,
                )
            }
            else -> null
        }
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.5f
        const val SPEED_STEP = 0.05f
        const val DEFAULT_SPEED = 1.0f

        fun snapSpeed(raw: Float): Float {
            val clamped = raw.coerceIn(MIN_SPEED, MAX_SPEED)
            return ((clamped / SPEED_STEP).roundToInt() * SPEED_STEP * 100f).roundToInt() / 100f
        }

        private fun computePercent(positionMs: Long, totalDurationMs: Long): Float {
            if (totalDurationMs <= 0L) return 0f
            return ((positionMs.toDouble() / totalDurationMs.toDouble()) * 100.0)
                .toFloat()
                .coerceIn(0f, 100f)
        }
    }
}
