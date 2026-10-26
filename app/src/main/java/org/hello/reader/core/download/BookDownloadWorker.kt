package org.hello.reader.core.download

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.hello.reader.app.HelloApplication
import org.hello.reader.core.database.dao.BookDao
import org.hello.reader.core.database.dao.BookDownloadDao
import org.hello.reader.core.database.entity.BookDownloadEntity
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.ServerProfile
import org.hello.reader.core.network.StorytellerApi
import org.hello.reader.core.util.NaturalFileOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * WorkManager worker that downloads aligned Readaloud `.epub` packages, standalone
 * `.epub` e-books, and standalone `.m4b`/`.mp3` audiobooks into strict multi-tenant
 * sandbox directories: `files/servers/{server_id}/books/{book_id}/`.
 *
 * - Explicitly binds every HTTP call to the job's `serverId` profile (`targetBaseUrl` +
 *   `Authorization: Bearer <token>`) so switching the active server while a download is
 *   queued or running never leaks tokens or routes to the wrong server.
 * - Rethrows [CancellationException], deletes partial `.part` files, and resets Room status
 *   to [DownloadStatus.NOT_DOWNLOADED] under [NonCancellable].
 * - Computes the SHA-256 digest while streaming to disk and verifies integrity when
 *   the server supplies an expected checksum.
 */
class BookDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
        val serverId = inputData.getString(KEY_SERVER_ID) ?: return@withContext Result.failure()
        val userId = inputData.getString(KEY_USER_ID) ?: return@withContext Result.failure()

        val container = (applicationContext as HelloApplication).container
        val bookDao = container.database.bookDao()
        val downloadDao = container.database.bookDownloadDao()
        val database = container.database
        val api = container.storytellerApi
        val vault = container.vault

        // Resolve the exact ServerProfile for this job's serverId (not activeProfile).
        val jobProfile = vault.getAllProfiles().firstOrNull {
            it.id == serverId && (userId.isEmpty() || it.userId == userId)
        } ?: return@withContext Result.failure()

        val requestedTypeStr = inputData.getString(KEY_REQUESTED_MEDIA_TYPE)
        val requestedMediaType = requestedTypeStr?.let {
            runCatching { BookMediaType.valueOf(it) }.getOrNull()
        }

        executeDownload(
            bookId = bookId,
            serverId = serverId,
            userId = userId,
            requestedMediaType = requestedMediaType,
            filesDir = applicationContext.filesDir,
            bookDao = bookDao,
            downloadDao = downloadDao,
            api = api,
            jobProfile = jobProfile,
            runInTransaction = { block -> database.withTransaction(block) },
            onProgressReport = { fraction -> setProgress(workDataOf(KEY_PROGRESS to fraction)) },
        )
    }

    companion object {
        internal suspend fun executeDownload(
            bookId: String,
            serverId: String,
            userId: String,
            requestedMediaType: BookMediaType?,
            filesDir: File,
            bookDao: BookDao,
            downloadDao: BookDownloadDao,
            api: StorytellerApi,
            jobProfile: ServerProfile,
            runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
            onProgressReport: suspend (Float) -> Unit = {},
        ): Result {
            val targetBaseUrl = jobProfile.baseUrl
            val bearerHeader = "Bearer ${jobProfile.authToken}"

            val book = bookDao.getBookById(bookId, serverId, userId)
                ?: return Result.failure()

            val targetMediaType = requestedMediaType ?: book.mediaType

            bookDao.updateDownloadProgress(
                bookId = bookId,
                serverId = serverId,
                userId = userId,
                status = DownloadStatus.DOWNLOADING,
                progress = 0f,
            )

            var tempFile: File? = null
            return try {
                val details = try {
                    val detailsResponse = api.getBookDetails(
                        bookId = bookId,
                        targetBaseUrl = targetBaseUrl,
                        authorization = bearerHeader,
                    )
                    if (detailsResponse.isSuccessful) detailsResponse.body() else null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                val targetDir = bookDirectory(filesDir, serverId, bookId).apply {
                    mkdirs()
                }

                val candidateUrls: List<String> = when (targetMediaType) {
                    BookMediaType.READALOUD -> listOfNotNull(
                        details?.alignedEpubDownloadUrl,
                        "api/v2/books/$bookId/files?format=readaloud",
                        "api/books/$bookId/synced",
                    ).distinct()
                    BookMediaType.EBOOK_ONLY -> listOfNotNull(
                        details?.ebookDownloadUrl,
                        details?.alignedEpubDownloadUrl,
                        "api/v2/books/$bookId/files?format=ebook",
                    ).distinct()
                    BookMediaType.AUDIO_ONLY -> listOfNotNull(
                        details?.audioDownloadUrl,
                        details?.audioStreamUrl,
                        "api/v2/books/$bookId/files?format=audiobook",
                    ).distinct()
                }

                val targetFileName = when (targetMediaType) {
                    BookMediaType.READALOUD -> "package.epub"
                    BookMediaType.EBOOK_ONLY -> "book.epub"
                    BookMediaType.AUDIO_ONLY -> "audiobook.m4b"
                }
                val destinationFile = File(targetDir, targetFileName)
                val partFile = File(targetDir, "$targetFileName.part").also { tempFile = it }

                var computedSha256: String? = null
                var downloadError: Exception? = null

                for (candidateUrl in candidateUrls) {
                    val isRelativeOrSameServer = !candidateUrl.startsWith("http://") && !candidateUrl.startsWith("https://") ||
                        candidateUrl.startsWith(targetBaseUrl)

                    try {
                        if (partFile.exists()) partFile.delete()
                        computedSha256 = streamToFile(
                            api = api,
                            assetUrl = candidateUrl,
                            targetBaseUrl = if (candidateUrl.startsWith("http://") || candidateUrl.startsWith("https://")) null else targetBaseUrl,
                            authorization = if (isRelativeOrSameServer) bearerHeader else null,
                            tempFile = partFile,
                            onProgress = { fraction ->
                                onProgressReport(fraction)
                                bookDao.updateDownloadProgress(
                                    bookId = bookId,
                                    serverId = serverId,
                                    userId = userId,
                                    status = DownloadStatus.DOWNLOADING,
                                    progress = fraction,
                                )
                            },
                        )
                        if (partFile.length() > 0) {
                            try {
                                validatePayload(partFile, targetMediaType)
                                downloadError = null
                                break
                            } catch (e: Exception) {
                                downloadError = e
                                partFile.delete()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        downloadError = e
                        partFile.delete()
                    }
                }

                if (computedSha256 == null || !partFile.exists() || partFile.length() == 0L) {
                    throw downloadError ?: IOException("All download candidates failed for book $bookId")
                }

                val expectedSha256 = details?.sha256?.takeIf { it.isNotBlank() }
                if (expectedSha256 != null && !expectedSha256.equals(computedSha256, ignoreCase = true)) {
                    partFile.delete()
                    throw IOException("SHA-256 mismatch: expected $expectedSha256, got $computedSha256")
                }

                if (destinationFile.exists()) destinationFile.delete()
                if (!partFile.renameTo(destinationFile)) {
                    throw IOException("Failed to move downloaded file into place: ${destinationFile.absolutePath}")
                }

                val resolvedLocalFilePath: String? = if (targetMediaType == BookMediaType.AUDIO_ONLY) book.localFilePath else destinationFile.absolutePath
                var resolvedLocalAudioPath: String? = if (targetMediaType == BookMediaType.AUDIO_ONLY) destinationFile.absolutePath else book.localAudioPath

                // If an audiobook was delivered as a ZIP bundle of audio files, unpack all tracks so ExoPlayer can read them
                if (targetMediaType == BookMediaType.AUDIO_ONLY && isZipFile(destinationFile)) {
                    val extractedTracks = unpackAudioZip(destinationFile, targetDir)
                    if (extractedTracks.isNotEmpty()) {
                        val audioExtractDir = File(targetDir, "audio")
                        resolvedLocalAudioPath = audioExtractDir.absolutePath
                    }
                }

                val finalDownloadPath = if (targetMediaType == BookMediaType.AUDIO_ONLY) resolvedLocalAudioPath else resolvedLocalFilePath
                val finalFile = File(finalDownloadPath ?: destinationFile.absolutePath)
                val sizeBytes = if (finalFile.isDirectory) {
                    finalFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else {
                    finalFile.length()
                }

                runInTransaction {
                    val latestBook = bookDao.getBookById(bookId, serverId, userId) ?: book
                    downloadDao.upsertDownload(
                        BookDownloadEntity(
                            bookId = bookId,
                            serverId = serverId,
                            userId = userId,
                            format = targetMediaType,
                            localPath = finalDownloadPath ?: destinationFile.absolutePath,
                            sizeBytes = sizeBytes,
                            downloadStatus = DownloadStatus.DOWNLOADED,
                            downloadProgress = 1.0f,
                            sha256 = computedSha256,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )

                    val allDownloads = downloadDao.getDownloadsForBook(bookId, serverId, userId)
                        .filter { it.downloadStatus == DownloadStatus.DOWNLOADED }

                    val readaloudDownload = allDownloads.firstOrNull { it.format == BookMediaType.READALOUD }
                    val ebookDownload = allDownloads.firstOrNull { it.format == BookMediaType.EBOOK_ONLY }
                    val audioDownload = allDownloads.firstOrNull { it.format == BookMediaType.AUDIO_ONLY }

                    val preservedTextPath = if (targetMediaType == BookMediaType.AUDIO_ONLY) {
                        latestBook.localFilePath ?: book.localFilePath
                    } else {
                        resolvedLocalFilePath
                    }
                    val preservedAudioPath = if (targetMediaType != BookMediaType.AUDIO_ONLY) {
                        latestBook.localAudioPath ?: book.localAudioPath
                    } else {
                        resolvedLocalAudioPath
                    }

                    val effectiveTextPath = readaloudDownload?.localPath ?: ebookDownload?.localPath ?: preservedTextPath
                    val effectiveAudioPath = audioDownload?.localPath ?: preservedAudioPath
                    val effectiveSha256 = readaloudDownload?.sha256
                        ?: ebookDownload?.sha256
                        ?: (if (targetMediaType != BookMediaType.AUDIO_ONLY) computedSha256 else (latestBook.sha256Checksum ?: book.sha256Checksum))
                        ?: audioDownload?.sha256
                        ?: computedSha256

                    val effectiveMediaType = when {
                        readaloudDownload != null -> BookMediaType.READALOUD
                        ebookDownload != null -> BookMediaType.EBOOK_ONLY
                        effectiveTextPath != null -> {
                            if (latestBook.mediaType == BookMediaType.READALOUD || targetMediaType == BookMediaType.READALOUD) {
                                BookMediaType.READALOUD
                            } else {
                                BookMediaType.EBOOK_ONLY
                            }
                        }
                        audioDownload != null || effectiveAudioPath != null -> BookMediaType.AUDIO_ONLY
                        else -> latestBook.mediaType
                    }

                    bookDao.updateMediaType(
                        bookId = bookId,
                        serverId = serverId,
                        userId = userId,
                        mediaType = effectiveMediaType,
                    )

                    bookDao.updateDownloadStatus(
                        bookId = bookId,
                        serverId = serverId,
                        userId = userId,
                        status = DownloadStatus.DOWNLOADED,
                        progress = 1.0f,
                        path = effectiveTextPath,
                        audioPath = effectiveAudioPath,
                        sha256 = effectiveSha256,
                    )
                }
                Result.success(
                    workDataOf(
                        KEY_LOCAL_PATH to (finalDownloadPath ?: destinationFile.absolutePath),
                        KEY_SHA256 to computedSha256,
                    ),
                )
            } catch (e: CancellationException) {
                tempFile?.delete()
                withContext(NonCancellable) {
                    runInTransaction {
                        restoreRemainingDownloadsAfterAbort(
                            bookDao = bookDao,
                            downloadDao = downloadDao,
                            fallbackBook = book,
                            bookId = bookId,
                            serverId = serverId,
                            userId = userId,
                            abortedMediaType = targetMediaType,
                            emptyStatus = DownloadStatus.NOT_DOWNLOADED,
                        )
                    }
                }
                throw e
            } catch (e: Exception) {
                tempFile?.delete()
                runInTransaction {
                    restoreRemainingDownloadsAfterAbort(
                        bookDao = bookDao,
                        downloadDao = downloadDao,
                        fallbackBook = book,
                        bookId = bookId,
                        serverId = serverId,
                        userId = userId,
                        abortedMediaType = targetMediaType,
                        emptyStatus = DownloadStatus.FAILED,
                    )
                }
                Result.failure()
            }
        }

        private suspend fun restoreRemainingDownloadsAfterAbort(
            bookDao: BookDao,
            downloadDao: BookDownloadDao,
            fallbackBook: org.hello.reader.core.database.entity.BookEntity,
            bookId: String,
            serverId: String,
            userId: String,
            abortedMediaType: BookMediaType,
            emptyStatus: DownloadStatus,
        ) {
            val latestBook = bookDao.getBookById(bookId, serverId, userId) ?: fallbackBook
            downloadDao.deleteDownload(bookId, serverId, userId, abortedMediaType)
            val remaining = downloadDao.getDownloadsForBook(bookId, serverId, userId)
                .filter { it.downloadStatus == DownloadStatus.DOWNLOADED }

            val readaloudDownload = remaining.firstOrNull { it.format == BookMediaType.READALOUD }
            val ebookDownload = remaining.firstOrNull { it.format == BookMediaType.EBOOK_ONLY }
            val audioDownload = remaining.firstOrNull { it.format == BookMediaType.AUDIO_ONLY }

            val preservedTextPath = if (abortedMediaType == BookMediaType.AUDIO_ONLY) {
                latestBook.localFilePath ?: fallbackBook.localFilePath
            } else {
                null
            }
            val preservedAudioPath = if (abortedMediaType != BookMediaType.AUDIO_ONLY) {
                latestBook.localAudioPath ?: fallbackBook.localAudioPath
            } else {
                null
            }

            val effectiveTextPath = readaloudDownload?.localPath ?: ebookDownload?.localPath ?: preservedTextPath
            val effectiveAudioPath = audioDownload?.localPath ?: preservedAudioPath
            val hasRemaining = remaining.isNotEmpty() || effectiveTextPath != null || effectiveAudioPath != null
            val remainingStatus = if (hasRemaining) DownloadStatus.DOWNLOADED else emptyStatus
            val effectiveSha256 = readaloudDownload?.sha256
                ?: ebookDownload?.sha256
                ?: audioDownload?.sha256
                ?: if (hasRemaining) (latestBook.sha256Checksum ?: fallbackBook.sha256Checksum) else null

            val effectiveMediaType = when {
                readaloudDownload != null -> BookMediaType.READALOUD
                ebookDownload != null -> BookMediaType.EBOOK_ONLY
                effectiveTextPath != null -> {
                    if (latestBook.mediaType == BookMediaType.READALOUD || fallbackBook.mediaType == BookMediaType.READALOUD) {
                        BookMediaType.READALOUD
                    } else {
                        BookMediaType.EBOOK_ONLY
                    }
                }
                audioDownload != null || effectiveAudioPath != null -> BookMediaType.AUDIO_ONLY
                else -> fallbackBook.mediaType
            }

            bookDao.updateMediaType(bookId, serverId, userId, effectiveMediaType)
            bookDao.updateDownloadStatus(
                bookId = bookId,
                serverId = serverId,
                userId = userId,
                status = remainingStatus,
                progress = if (hasRemaining) 1.0f else 0f,
                path = effectiveTextPath,
                audioPath = effectiveAudioPath,
                sha256 = effectiveSha256,
            )
        }

    private suspend fun streamToFile(
        api: StorytellerApi,
        assetUrl: String,
        targetBaseUrl: String?,
        authorization: String?,
        tempFile: File,
        onProgress: suspend (Float) -> Unit,
    ): String {
        val response = api.downloadAsset(
            assetUrl = assetUrl,
            targetBaseUrl = targetBaseUrl,
            authorization = authorization,
        )
        if (!response.isSuccessful) {
            throw IOException("Asset download failed with HTTP ${response.code()}")
        }
        val body = response.body() ?: throw IOException("Empty download response body")
        val totalBytes = body.contentLength()
        val digest = MessageDigest.getInstance("SHA-256")

        body.byteStream().use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead = 0L
                var lastReportedPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesRead += read
                    if (totalBytes > 0L) {
                        val fraction = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        val percent = (fraction * 100).toInt()
                        if (percent > lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(fraction)
                        }
                    }
                }
                output.fd.sync()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isZipFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            file.inputStream().use { input ->
                val b0 = input.read()
                val b1 = input.read()
                val b2 = input.read()
                val b3 = input.read()
                b0 == 0x50 && b1 == 0x4B && (b2 == 0x03 || b2 == 0x05 || b2 == 0x07) && (b3 == 0x04 || b3 == 0x06 || b3 == 0x08)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun validatePayload(file: File, mediaType: BookMediaType) {
        if (!file.exists() || file.length() < 4) {
            throw IOException("Downloaded file is empty or corrupted (size=${file.length()} bytes)")
        }
        when (mediaType) {
            BookMediaType.READALOUD, BookMediaType.EBOOK_ONLY -> {
                if (!isZipFile(file)) {
                    throw IOException("Downloaded EPUB payload missing ZIP magic bytes")
                }
                var hasEpubStructure = false
                try {
                    java.util.zip.ZipFile(file).use { zip ->
                        hasEpubStructure = zip.getEntry("mimetype") != null ||
                            zip.getEntry("META-INF/container.xml") != null ||
                            zip.entries().asSequence().any { it.name.endsWith(".opf", ignoreCase = true) }
                    }
                } catch (e: Exception) {
                    throw IOException("Downloaded EPUB payload is not a valid ZIP container: ${e.message}", e)
                }
                if (!hasEpubStructure) {
                    throw IOException("Downloaded EPUB missing required EPUB container structure (mimetype or container.xml)")
                }
            }
            BookMediaType.AUDIO_ONLY -> {
                if (isZipFile(file)) {
                    val audioExts = setOf("mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav")
                    var hasAudio = false
                    try {
                        java.util.zip.ZipFile(file).use { zip ->
                            hasAudio = zip.entries().asSequence().any { entry ->
                                !entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in audioExts
                            }
                        }
                    } catch (e: Exception) {
                        throw IOException("Downloaded audio ZIP is corrupted: ${e.message}", e)
                    }
                    if (!hasAudio) {
                        throw IOException("Downloaded audio ZIP contains no supported audio files")
                    }
                } else {
                    val header = ByteArray(512)
                    val read = file.inputStream().use { it.read(header) }
                    val headerText = String(header, 0, maxOf(0, read), Charsets.UTF_8).trim().lowercase()
                    if (headerText.startsWith("<!doctype") || headerText.startsWith("<html") ||
                        headerText.startsWith("{\"error\"") || headerText.startsWith("{\"detail\"")
                    ) {
                        throw IOException("Downloaded audio payload is an HTML/JSON error response")
                    }
                }
            }
        }
    }

    private fun unpackAudioZip(zipFile: File, targetDir: File): List<File> {
        val audioExts = setOf("mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav")
        val audioFiles = mutableListOf<File>()
        val audioExtractDir = File(targetDir, "audio")
        if (audioExtractDir.exists()) {
            audioExtractDir.deleteRecursively()
        }
        audioExtractDir.mkdirs()

        try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext !in audioExts) continue

                    val outFile = File(audioExtractDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(audioExtractDir.canonicalPath + File.separator)) {
                        continue
                    }
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                    audioFiles += outFile
                }
            }
        } catch (e: Exception) {
            audioExtractDir.deleteRecursively()
            throw IOException("Failed to unpack audio zip: ${e.message}", e)
        }

        if (audioFiles.isNotEmpty()) {
            zipFile.delete()
        }
        return audioFiles.sortedWith(NaturalFileOrder)
    }

        const val KEY_BOOK_ID = "book_id"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_SHA256 = "sha256"
        const val KEY_REQUESTED_MEDIA_TYPE = "requested_media_type"

        fun uniqueWorkName(serverId: String, bookId: String, format: BookMediaType? = null): String =
            if (format != null) "hello_book_download_${serverId}_${bookId}_${format.name.lowercase()}"
            else "hello_book_download_${serverId}_$bookId"

        /**
         * Sandbox directory per `ARCHITECTURE.md` Section 7:
         * `files/servers/{server_id}/books/{book_id}/`
         */
        fun bookDirectory(filesDir: File, serverId: String, bookId: String): File =
            File(filesDir, "servers/$serverId/books/$bookId")

        fun enqueue(
            context: Context,
            serverId: String,
            userId: String,
            bookId: String,
            requestedMediaType: String? = null,
        ) {
            val format = requestedMediaType?.let { runCatching { BookMediaType.valueOf(it) }.getOrNull() }
            val data: Data = workDataOf(
                KEY_BOOK_ID to bookId,
                KEY_SERVER_ID to serverId,
                KEY_USER_ID to userId,
                KEY_REQUESTED_MEDIA_TYPE to requestedMediaType,
            )
            val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(serverId)
                .addTag(bookId)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(serverId, bookId, format),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, serverId: String, bookId: String, format: BookMediaType? = null) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(serverId, bookId, format))
            if (format == null) {
                BookMediaType.entries.forEach { f ->
                    WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(serverId, bookId, f))
                }
            }
        }
    }
}
