package org.hello.reader.core.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.hello.reader.core.database.dao.BookDao
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.LocalLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Imports local EPUB (.epub) and standalone audiobook (.m4b, .mp3) files from device storage
 * into the app's sandboxed storage and registers them in Room as local books.
 */
class LocalBookImporter(
    private val context: Context,
    private val bookDao: BookDao,
) {

    /**
     * Copies the document into app storage, extracts metadata/cover, and registers it.
     * Fails fast for formats the reader can't open; a failed or cancelled import
     * leaves no partial files behind.
     */
    suspend fun importFromUri(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        val extension = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in SUPPORTED_EXTENSIONS }
            ?: extensionForMimeType(context.contentResolver.getType(uri))
            ?: return@withContext Result.failure(UnsupportedBookFormatException(displayName ?: uri.toString()))
        val fileName = displayName ?: "imported_book.$extension"

        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        val bookId = LocalLibrary.ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
        val destinationFile = File(booksDir, "$bookId.$extension")

        fun cleanUp() {
            destinationFile.delete()
            coversDir.listFiles { f -> f.name.startsWith(bookId) }?.forEach { it.delete() }
        }

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destinationFile).use { output -> input.copyTo(output) }
            } ?: throw IOException("Cannot open $fileName")

            val baseFileName = fileName.substringBeforeLast('.')
            val (mediaType, title, authors, durationSeconds, coverFile) = when (extension) {
                "epub" -> parseEpubMetadata(destinationFile, coversDir, bookId, baseFileName)
                "m4b", "mp3", "m4a", "aac" -> parseAudioMetadata(destinationFile, coversDir, bookId, baseFileName)
                else -> error("unreachable: $extension was validated above")
            }

            val book = Book(
                id = bookId,
                serverId = LocalLibrary.SERVER_ID,
                userId = LocalLibrary.USER_ID,
                title = title,
                authors = authors,
                coverUrl = coverFile?.toURI()?.toString(),
                mediaType = mediaType,
                durationSeconds = durationSeconds,
                downloadStatus = DownloadStatus.DOWNLOADED,
                downloadProgress = 1.0f,
                localFilePath = destinationFile.absolutePath,
                localAudioPath = if (mediaType == BookMediaType.AUDIO_ONLY) destinationFile.absolutePath else null,
                createdAt = System.currentTimeMillis(),
                isSyncedWithServer = false,
                hasReadaloud = mediaType == BookMediaType.READALOUD,
                hasEbook = mediaType == BookMediaType.EBOOK_ONLY || mediaType == BookMediaType.READALOUD,
                hasAudiobook = mediaType == BookMediaType.AUDIO_ONLY || mediaType == BookMediaType.READALOUD,
            )

            bookDao.upsertBooks(listOf(BookEntity.fromDomain(book, LocalLibrary.USER_ID)))
            Result.success(book)
        } catch (e: CancellationException) {
            cleanUp()
            throw e
        } catch (e: Exception) {
            cleanUp()
            Result.failure(e)
        }
    }

    suspend fun deleteLocalBook(book: Book): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            book.localFilePath?.let { File(it).deleteRecursively() }
            book.localAudioPath?.let { File(it).deleteRecursively() }
            book.coverUrl?.let {
                runCatching { File(java.net.URI(it)).delete() }
            }
            bookDao.deleteLocalBook(book.id)
        }
    }

    private data class ParsedMetadata(
        val mediaType: BookMediaType,
        val title: String,
        val authors: List<String>,
        val durationSeconds: Long,
        val coverFile: File?,
    )

    private fun parseEpubMetadata(
        epubFile: File,
        coversDir: File,
        bookId: String,
        fallbackTitle: String,
    ): ParsedMetadata {
        var title: String? = null
        val authors = mutableListOf<String>()
        var hasSmil = false
        var coverFile: File? = null

        runCatching {
            ZipFile(epubFile).use { zip ->
                val entries = zip.entries().asSequence().associateBy { it.name }
                hasSmil = entries.keys.any { it.endsWith(".smil", ignoreCase = true) }

                // Find OPF file via META-INF/container.xml
                val containerEntry = entries["META-INF/container.xml"]
                val opfPath = containerEntry?.let { entry ->
                    val containerXml = zip.getInputStream(entry).bufferedReader().readText()
                    Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerXml)?.groupValues?.get(1)
                } ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }

                if (opfPath != null && opfPath in entries) {
                    val opfXml = zip.getInputStream(entries[opfPath]!!).bufferedReader().readText()
                    val titleMatch = Regex("""<dc:title\b[^>]*>(.*?)</dc:title>""", RegexOption.IGNORE_CASE)
                        .find(opfXml)?.groupValues?.get(1)?.trim()
                    if (!titleMatch.isNullOrBlank()) {
                        title = cleanHtmlEntities(titleMatch)
                    }

                    Regex("""<dc:creator\b[^>]*>(.*?)</dc:creator>""", RegexOption.IGNORE_CASE)
                        .findAll(opfXml).forEach { match ->
                            val author = cleanHtmlEntities(match.groupValues[1].trim())
                            if (author.isNotBlank()) authors.add(author)
                        }

                    // Look for cover image reference in manifest
                    val coverHref = findCoverHref(opfXml)
                    if (coverHref != null) {
                        val resolvedCoverPath = resolveRelativePath(opfPath, coverHref)
                        val coverEntry = entries[resolvedCoverPath]
                        if (coverEntry != null) {
                            val ext = resolvedCoverPath.substringAfterLast('.', "jpg")
                            val targetCover = File(coversDir, "$bookId.$ext")
                            zip.getInputStream(coverEntry).use { inStream ->
                                FileOutputStream(targetCover).use { outStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            coverFile = targetCover
                        }
                    }
                }
            }
        }

        return ParsedMetadata(
            mediaType = if (hasSmil) BookMediaType.READALOUD else BookMediaType.EBOOK_ONLY,
            title = title ?: fallbackTitle,
            authors = if (authors.isEmpty()) listOf("Unknown Author") else authors,
            durationSeconds = 0L,
            coverFile = coverFile,
        )
    }

    private fun findCoverHref(opfXml: String): String? {
        // 1. Check item with properties="cover-image"
        val itemWithProp = Regex("""<item\b[^>]*\bproperties\s*=\s*["'][^"']*cover-image[^"']*["'][^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(opfXml)?.groupValues?.get(1)
        if (itemWithProp != null) return itemWithProp

        val itemWithPropReversed = Regex("""<item\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*\bproperties\s*=\s*["'][^"']*cover-image[^"']*["']""", RegexOption.IGNORE_CASE)
            .find(opfXml)?.groupValues?.get(1)
        if (itemWithPropReversed != null) return itemWithPropReversed

        // 2. Check meta name="cover" content="<id>"
        val metaCoverId = Regex("""<meta\b[^>]*\bname\s*=\s*["']cover["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(opfXml)?.groupValues?.get(1)
        if (metaCoverId != null) {
            val itemForId = Regex("""<item\b[^>]*\bid\s*=\s*["']$metaCoverId["'][^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(opfXml)?.groupValues?.get(1)
            if (itemForId != null) return itemForId
        }

        // 3. Fallback: item with id="cover" or id="cover-image"
        val itemCover = Regex("""<item\b[^>]*\bid\s*=\s*["'](?:cover|cover-image)["'][^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(opfXml)?.groupValues?.get(1)
        return itemCover
    }

    private fun parseAudioMetadata(
        audioFile: File,
        coversDir: File,
        bookId: String,
        fallbackTitle: String,
    ): ParsedMetadata {
        var title: String? = null
        val authors = mutableListOf<String>()
        var durationSeconds = 0L
        var coverFile: File? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioFile.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            if (!artist.isNullOrBlank()) {
                authors.add(artist)
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durationSeconds = durationMs / 1000L

            val picture = retriever.embeddedPicture
            if (picture != null) {
                val targetCover = File(coversDir, "$bookId.jpg")
                targetCover.writeBytes(picture)
                coverFile = targetCover
            }
        } catch (_: Exception) {
            // Fallback to defaults
        } finally {
            retriever.release()
        }

        return ParsedMetadata(
            mediaType = BookMediaType.AUDIO_ONLY,
            title = title?.ifBlank { null } ?: fallbackTitle,
            authors = if (authors.isEmpty()) listOf("Unknown Narrator") else authors,
            durationSeconds = durationSeconds,
            coverFile = coverFile,
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun resolveRelativePath(baseFile: String, relativeHref: String): String {
        if (!baseFile.contains("/")) return relativeHref
        val dir = baseFile.substringBeforeLast('/')
        val combined = "$dir/$relativeHref"
        val segments = combined.split("/")
        val resolved = mutableListOf<String>()
        for (segment in segments) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                else -> resolved.add(segment)
            }
        }
        return resolved.joinToString("/")
    }

    private fun cleanHtmlEntities(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("epub", "m4b", "m4a", "mp3", "aac")

        /** For providers (e.g. cloud drives) whose display names have no extension. */
        internal fun extensionForMimeType(mime: String?): String? = when (mime?.lowercase()) {
            "application/epub+zip" -> "epub"
            "audio/mp4", "audio/x-m4b", "audio/m4b" -> "m4b"
            "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/aac", "audio/x-aac" -> "aac"
            else -> null
        }
    }
}

/** The picked file isn't an EPUB or a supported audiobook. */
class UnsupportedBookFormatException(val fileName: String) :
    IllegalArgumentException("$fileName isn't a supported book. Choose an .epub, .m4b, .m4a, .mp3 or .aac file.")
