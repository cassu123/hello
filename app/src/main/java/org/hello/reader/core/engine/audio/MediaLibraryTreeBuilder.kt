package org.hello.reader.core.engine.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import coil.imageLoader
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import java.io.File
import org.hello.reader.R
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType

/**
 * Pure helper for building the Media3 [androidx.media3.session.MediaLibraryService] browse tree
 * for Android Auto and MediaBrowser controllers.
 *
 * Structure:
 * - `"root"` -> `["continue_listening", "downloaded", "recent"]`
 * - Each category -> filtered list of audio-eligible books ([BookMediaType.READALOUD],
 *   [BookMediaType.AUDIO_ONLY], or books with `hasReadaloud`/`hasAudiobook`).
 * - Book item artwork is served via [FileProvider] content URIs (`org.hello.reader.fileprovider`),
 *   preventing Android Auto head units from failing on private authenticated HTTPS URLs.
 */
@OptIn(UnstableApi::class)
object MediaLibraryTreeBuilder {

    const val ROOT_ID = "root"
    const val CATEGORY_CONTINUE_LISTENING = "continue_listening"
    const val CATEGORY_DOWNLOADED = "downloaded"
    const val CATEGORY_RECENT = "recent"
    const val MEDIA_ID_BOOK_PREFIX = "book:"
    const val FILE_PROVIDER_AUTHORITY = "org.hello.reader.fileprovider"
    const val MAX_RECENT_ITEMS = 25

    private val COVER_EXTENSIONS = listOf("jpg", "png", "webp", "jpeg")

    /**
     * Checks if a book is eligible for audio listening in Android Auto / media browse.
     */
    fun isAudioEligible(book: Book): Boolean =
        book.mediaType == BookMediaType.READALOUD ||
            book.mediaType == BookMediaType.AUDIO_ONLY ||
            book.hasReadaloud ||
            book.hasAudiobook

    /**
     * Filters a list of books to only those with audio capability.
     */
    fun filterAudioBooks(books: List<Book>): List<Book> =
        books.filter(::isAudioEligible)

    /**
     * Resolves a local content URI for the book cover via [FileProvider], or null if
     * no local cover exists. HTTPS URLs are avoided for Android Auto compatibility.
     */
    fun resolveCoverUri(context: Context, bookId: String, coverUrl: String? = null): Uri? {
        val coversDir = File(context.filesDir, "covers")
        for (ext in COVER_EXTENSIONS) {
            val file = File(coversDir, "$bookId.$ext")
            if (file.isFile && file.length() > 0L) {
                return runCatching {
                    FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
                }.getOrNull()
            }
        }
        if (coverUrl != null && (coverUrl.startsWith("content://") || coverUrl.startsWith("file://"))) {
            return runCatching { coverUrl.toUri() }.getOrNull()
        }
        return null
    }

    /**
     * Ensures cover artwork is cached locally on disk in filesDir/covers/ so it can be served
     * securely via FileProvider content URIs to Android Auto without HTTPS auth issues.
     */
    suspend fun ensureCoverCached(context: Context, bookId: String, coverUrl: String?): Uri? {
        val existing = resolveCoverUri(context, bookId, coverUrl)
        if (existing != null) return existing
        if (coverUrl.isNullOrBlank() || (!coverUrl.startsWith("http://") && !coverUrl.startsWith("https://"))) {
            return null
        }
        return runCatching {
            val imageLoader = context.imageLoader
            val request = coil.request.ImageRequest.Builder(context)
                .data(coverUrl)
                .size(320, 480)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            if (result is coil.request.SuccessResult) {
                val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    val coversDir = File(context.filesDir, "covers")
                    if (!coversDir.exists()) coversDir.mkdirs()
                    val targetFile = File(coversDir, "$bookId.jpg")
                    targetFile.outputStream().use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, targetFile)
                } else null
            } else null
        }.getOrNull()
    }

    /**
     * Builds the root media item for the library service.
     */
    fun buildRootItem(title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    fun buildRootItem(context: Context): MediaItem =
        buildRootItem(context.getString(R.string.auto_root_title))

    /**
     * Builds a category media item (`continue_listening`, `downloaded`, or `recent`).
     */
    fun buildCategoryItem(categoryId: String, title: String): MediaItem? {
        if (categoryId !in listOf(CATEGORY_CONTINUE_LISTENING, CATEGORY_DOWNLOADED, CATEGORY_RECENT)) {
            return null
        }
        return MediaItem.Builder()
            .setMediaId(categoryId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()
    }

    fun buildCategoryItem(context: Context, categoryId: String): MediaItem? {
        val titleRes = when (categoryId) {
            CATEGORY_CONTINUE_LISTENING -> R.string.auto_continue_listening
            CATEGORY_DOWNLOADED -> R.string.auto_downloaded
            CATEGORY_RECENT -> R.string.auto_recent
            else -> return null
        }
        return buildCategoryItem(categoryId, context.getString(titleRes))
    }

    /**
     * Builds all root category items in standard display order.
     */
    fun buildCategoryItems(context: Context): List<MediaItem> =
        listOf(
            CATEGORY_CONTINUE_LISTENING,
            CATEGORY_DOWNLOADED,
            CATEGORY_RECENT,
        ).mapNotNull { buildCategoryItem(context, it) }

    /**
     * Builds a playable [MediaItem] representing a book for Android Auto.
     */
    fun buildBookItem(
        book: Book,
        coverUri: Uri? = null,
    ): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.authors.joinToString(", ").ifBlank { null })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)

        if (coverUri != null) {
            metadataBuilder.setArtworkUri(coverUri)
        }

        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_BOOK_PREFIX${book.id}")
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    fun buildBookItem(
        context: Context,
        book: Book,
        coverUri: Uri? = resolveCoverUri(context, book.id, book.coverUrl),
    ): MediaItem = buildBookItem(book, coverUri)
}
