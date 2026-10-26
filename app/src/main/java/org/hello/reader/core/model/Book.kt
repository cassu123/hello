package org.hello.reader.core.model

import kotlinx.serialization.Serializable

/**
 * Supported book formats per user product decision:
 * - [READALOUD]: Synced EPUB 3 package containing SMIL Media Overlays + embedded audio.
 * - [EBOOK_ONLY]: Standalone EPUB 3 text-only book.
 * - [AUDIO_ONLY]: Standalone audiobook (M4B / MP3).
 */
@Serializable
enum class BookMediaType {
    READALOUD,
    EBOOK_ONLY,
    AUDIO_ONLY,
}

@Serializable
enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

@Serializable
data class Book(
    val id: String,
    val serverId: String,
    val userId: String = "",
    val title: String,
    val authors: List<String>,
    val narrators: List<String> = emptyList(),
    val coverUrl: String? = null,
    val description: String? = null,
    val mediaType: BookMediaType,
    val durationSeconds: Long = 0L,
    val totalChapters: Int = 0,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val localFilePath: String? = null,
    val localAudioPath: String? = null,
    val sha256Checksum: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val lastOpenedAt: Long? = null,
    val isSyncedWithServer: Boolean = true,
    val hasReadaloud: Boolean = false,
    val hasEbook: Boolean = false,
    val hasAudiobook: Boolean = false,
    val readingStatus: String? = null,
    val readingStatusLabel: String? = null,
    val statusUuid: String? = null,
    val rating: Float? = null,
    val publicationDate: String? = null,
    val language: String? = null,
    val publisher: String? = null,
    val isbn: String? = null,
    val externalRatings: String? = null,
) {
    val isRead: Boolean
        get() = readingStatus?.lowercase() in listOf("read", "finished", "completed") ||
            readingStatusLabel?.lowercase() in listOf("read", "finished", "completed") ||
            statusUuid?.lowercase() in listOf("read", "finished", "completed")

    val isToRead: Boolean
        get() = readingStatus?.lowercase() in listOf("to_read", "to-read", "toread", "want_to_read") ||
            readingStatusLabel?.lowercase() in listOf("to read", "to-read", "want to read") ||
            statusUuid?.lowercase() in listOf("to_read", "to-read", "toread", "want_to_read")

    val isDownloaded: Boolean
        get() = downloadStatus == DownloadStatus.DOWNLOADED &&
            (!localFilePath.isNullOrBlank() || !localAudioPath.isNullOrBlank())

    fun availableFormats(): List<BookMediaType> {
        val formats = mutableListOf<BookMediaType>()
        if (hasReadaloud) formats += BookMediaType.READALOUD
        if (hasEbook) formats += BookMediaType.EBOOK_ONLY
        if (hasAudiobook) formats += BookMediaType.AUDIO_ONLY
        if (formats.isEmpty()) {
            formats += mediaType
        }
        return formats
    }
}

/**
 * Books imported from device storage live in their own scope, independent of any
 * Storyteller server: their progress, highlights and bookmarks must survive
 * connecting, switching or disconnecting servers, and must never be pushed upstream.
 */
object LocalLibrary {
    const val SERVER_ID = "local"
    const val USER_ID = "local"

    /** Prefix of ids minted by [org.hello.reader.core.data.LocalBookImporter]; never used by Storyteller. */
    const val ID_PREFIX = "local_"

    fun isLocalBookId(bookId: String): Boolean = bookId.startsWith(ID_PREFIX)
}

val Book.isLocal: Boolean get() = serverId == LocalLibrary.SERVER_ID
