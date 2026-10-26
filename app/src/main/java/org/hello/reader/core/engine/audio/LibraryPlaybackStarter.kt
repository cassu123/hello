package org.hello.reader.core.engine.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.hello.reader.app.AppContainer
import org.hello.reader.core.engine.BookContentLoader
import org.hello.reader.core.engine.smil.AudioTrackItem
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.SyncCheckpoint

data class PreparedLibraryPlayback(
    val book: Book,
    val handoff: BackgroundHandoff,
    val tracks: List<AudioTrackItem>,
    val startMs: Long,
    val coverUri: Uri?,
    val mediaItems: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

object LibraryPlaybackStarter {

    suspend fun preparePlayback(
        context: Context,
        container: AppContainer,
        bookId: String,
        callingPackage: String? = null,
    ): PreparedLibraryPlayback? = withContext(Dispatchers.IO) {
        val books = container.bookRepository.observeLibrary().first()
        val book = books.find { it.id == bookId }
            ?: container.bookRepository.observeContinueReading().first().find { it.id == bookId }
            ?: return@withContext null

        if (!MediaLibraryTreeBuilder.isAudioEligible(book)) return@withContext null

        val downloads = container.database.bookDownloadDao().getDownloadsForBook(bookId, book.serverId, book.userId)
        val readaloudDownload = downloads.firstOrNull { it.format == BookMediaType.READALOUD && it.downloadStatus == DownloadStatus.DOWNLOADED }
        val audioDownload = downloads.firstOrNull { it.format == BookMediaType.AUDIO_ONLY && it.downloadStatus == DownloadStatus.DOWNLOADED }

        val hasReadaloud = readaloudDownload != null || (book.mediaType == BookMediaType.READALOUD && book.localFilePath != null)
        val resolvedTextPath = readaloudDownload?.localPath ?: book.localFilePath
        val resolvedAudioPath = if (hasReadaloud) {
            null
        } else {
            audioDownload?.localPath ?: book.localAudioPath?.takeIf { !it.endsWith(".epub", ignoreCase = true) }
        }

        val content = BookContentLoader.loadBookContent(book, resolvedTextPath, resolvedAudioPath)
            ?: return@withContext null

        if (content.audioTracks.isEmpty()) return@withContext null

        val localCheckpoint = container.database.syncCheckpointDao().getCheckpoint(bookId, book.serverId, book.userId)?.toDomain()
        val remoteCheckpoint = withTimeoutOrNull(3000L) {
            runCatching { container.bookRepository.syncBookPosition(bookId).getOrNull() }.getOrNull()
        }
        val effectiveCheckpoint = remoteCheckpoint ?: localCheckpoint
        val startPosMs = BookContentLoader.resolveCheckpointAudioPosition(effectiveCheckpoint, content)

        val coverUri = MediaLibraryTreeBuilder.ensureCoverCached(context, book.id, book.coverUrl)
        if (coverUri != null && coverUri.scheme == "content" && callingPackage != null) {
            runCatching {
                context.grantUriPermission(callingPackage, coverUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val (initialIndex, initialLocalPos) = resolveTrackPosition(startPosMs, content.audioTracks)

        val finalCheckpoint = effectiveCheckpoint ?: SyncCheckpoint(
            bookId = book.id,
            serverId = book.serverId,
            userId = book.userId,
            readingCfi = null,
            chapterIndex = 0,
            chapterTitle = content.chapters[0]?.title,
            audioPositionMs = startPosMs,
            totalDurationMs = book.durationSeconds * 1000L,
            progressPercent = 0f,
            activeSentenceId = content.smilNodes.firstOrNull()?.fragmentId,
            updatedAt = System.currentTimeMillis(),
            isDirty = false,
        )

        val settings = container.readerPreferencesRepository.settings.first()
        val speed = settings.playbackSpeed

        val handoff = BackgroundHandoff(
            book = book,
            checkpoint = finalCheckpoint,
            smilNodes = content.smilNodes,
            chapters = content.chapters,
            speed = speed,
        )

        val mediaItems = content.audioTracks.mapIndexed { idx, track ->
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(book.title)
                .setArtist(book.authors.joinToString(", ").ifBlank { null })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .setTrackNumber(idx + 1)

            if (coverUri != null) {
                metadataBuilder.setArtworkUri(coverUri)
            }

            MediaItem.Builder()
                .setMediaId("${PlaybackSession.mediaIdPrefix(book.id)}$idx")
                .setUri(Uri.fromFile(track.file))
                .setMediaMetadata(metadataBuilder.build())
                .build()
        }

        PreparedLibraryPlayback(
            book = book,
            handoff = handoff,
            tracks = content.audioTracks,
            startMs = startPosMs,
            coverUri = coverUri,
            mediaItems = mediaItems,
            startIndex = initialIndex,
            startPositionMs = initialLocalPos,
        )
    }

    private fun resolveTrackPosition(positionMs: Long, tracks: List<AudioTrackItem>): Pair<Int, Long> {
        if (tracks.isEmpty()) return Pair(0, 0L)
        var cumulativeMs = 0L
        for ((idx, track) in tracks.withIndex()) {
            val trackEndMs = cumulativeMs + track.durationMs
            if (positionMs in cumulativeMs until trackEndMs) {
                return Pair(idx, (positionMs - cumulativeMs).coerceAtLeast(0L))
            }
            cumulativeMs = trackEndMs
        }
        return Pair(tracks.lastIndex, (positionMs - (cumulativeMs - tracks.last().durationMs)).coerceAtLeast(0L))
    }
}
