package org.hello.reader.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Bi-directional reading and audio playback checkpoint scoped by ([bookId], [serverId], [userId]).
 */
@Serializable
data class SyncCheckpoint(
    val bookId: String,
    val serverId: String,
    val userId: String,
    val readingCfi: String?,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val audioPositionMs: Long,
    val totalDurationMs: Long,
    val progressPercent: Float,
    val activeSentenceId: String?,
    val updatedAt: Long = System.currentTimeMillis(),
    val isDirty: Boolean = false,
    val chapterProgression: Float? = null,
) {
    /**
     * True when this position and [remote] point at clearly different places in the book, so the
     * user should pick one: audio clocks more than [thresholdMs] apart, or reading progress at least
     * [TEXT_ONLY_PROGRESS_THRESHOLD_PERCENT] apart. Storyteller v2 positions are Readium locators
     * with no audio clock, so audio is compared only when both sides have one; otherwise a local
     * listening position would never match the server's and the prompt would repeat forever.
     */
    fun conflictsWith(remote: SyncCheckpoint, thresholdMs: Long = CONFLICT_THRESHOLD_MS): Boolean {
        if (bookId != remote.bookId || serverId != remote.serverId) return false
        if (activeSentenceId != null && activeSentenceId == remote.activeSentenceId && readingCfi == remote.readingCfi) {
            return false
        }
        if (hasAudioClock && remote.hasAudioClock) {
            return abs(audioPositionMs - remote.audioPositionMs) > thresholdMs
        }
        return abs(progressPercent - remote.progressPercent) >= TEXT_ONLY_PROGRESS_THRESHOLD_PERCENT
    }

    private val hasAudioClock: Boolean get() = totalDurationMs > 0L || audioPositionMs > 0L

    companion object {
        const val CONFLICT_THRESHOLD_MS: Long = 60_000L
        const val TEXT_ONLY_PROGRESS_THRESHOLD_PERCENT: Float = 5.0f
    }
}

/**
 * Represents an unresolved position conflict between local and remote checkpoints
 * that exceeds the 60-second threshold and requires user confirmation in the UI.
 */
data class SyncConflict(
    val bookId: String,
    val local: SyncCheckpoint,
    val remote: SyncCheckpoint,
)
