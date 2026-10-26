package org.hello.reader.feature.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.core.net.toUri
import androidx.glance.appwidget.updateAll
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hello.reader.app.HelloApplication
import org.hello.reader.core.data.BookRepository
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.database.dao.SyncCheckpointDao
import org.hello.reader.core.engine.audio.MediaLibraryTreeBuilder
import org.hello.reader.core.engine.audio.PlaybackSession
import org.hello.reader.core.model.Book
import org.hello.reader.core.security.SecurityVault
import org.hello.reader.core.util.DeviceProfile

data class ContinueReadingWidgetData(
    val bookId: String?,
    val title: String,
    val author: String,
    val coverBitmap: Bitmap?,
    val progressPercent: Float,
    val hasAudio: Boolean,
    val isPlaying: Boolean,
    val isEInk: Boolean,
    val isEmpty: Boolean,
) {
    companion object {
        val EMPTY = ContinueReadingWidgetData(
            bookId = null,
            title = "",
            author = "",
            coverBitmap = null,
            progressPercent = 0f,
            hasAudio = false,
            isPlaying = false,
            isEInk = false,
            isEmpty = true,
        )
    }
}

/**
 * Manages data loading and throttled update requests for [ContinueReadingWidget].
 */
object ContinueReadingWidgetUpdater {

    private const val THROTTLE_MS = 3_000L
    private val lastUpdateMs = AtomicLong(0L)
    @Volatile
    private var pendingJob: Job? = null
    private val updateLock = Any()
    private val updaterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false
    private val initCompleted = AtomicBoolean(false)
    private val updateRequestedBeforeInit = AtomicBoolean(false)

    internal fun reset() {
        initialized = false
        initCompleted.set(false)
        updateRequestedBeforeInit.set(false)
        lastUpdateMs.set(0L)
        synchronized(updateLock) {
            pendingJob?.cancel()
            pendingJob = null
        }
    }

    /**
     * Observes server profile switches and playback play/pause transitions,
     * ensuring the widget updates immediately whenever profile or playback status changes.
     * Evaluates [vaultProvider] and [playbackSessionProvider] on [ioDispatcher] off the main thread,
     * collects flows on [mainDispatcher], and replays any update requested before init finished.
     */
    fun initialize(
        context: Context,
        scope: CoroutineScope = updaterScope,
        vaultProvider: () -> SecurityVault,
        playbackSessionProvider: () -> PlaybackSession,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        onUpdateImmediate: suspend (Context) -> Unit = { updateImmediately(it) },
    ) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext ?: context
        scope.launch(ioDispatcher) {
            val vault = vaultProvider()
            val playbackSession = playbackSessionProvider()
            initCompleted.set(true)
            if (updateRequestedBeforeInit.getAndSet(false)) {
                onUpdateImmediate(appContext)
            }
            withContext(mainDispatcher) {
                launch {
                    vault.activeProfile
                        .distinctUntilChangedBy { it?.id to it?.userId }
                        .collect {
                            onUpdateImmediate(appContext)
                        }
                }
                launch {
                    playbackSession.nowPlaying
                        .map { it?.isPlaying }
                        .distinctUntilChanged()
                        .collect {
                            onUpdateImmediate(appContext)
                        }
                }
            }
        }
    }

    /**
     * Loads the current in-progress book scoped strictly to the currently active server profile
     * (or local books). Never leaks or displays titles/covers from inactive server profiles.
     */
    suspend fun loadWidgetData(context: Context): ContinueReadingWidgetData {
        val app = context.applicationContext as? HelloApplication ?: return ContinueReadingWidgetData.EMPTY
        val container = app.container

        return loadWidgetDataFromRepositories(
            bookRepository = container.bookRepository,
            checkpointDao = container.database.syncCheckpointDao(),
            playbackSession = container.playbackSession,
            readerPreferencesRepository = container.readerPreferencesRepository,
            loadCover = { book -> loadCoverBitmap(context, book) },
            isEInkDevice = DeviceProfile.isCurrentDeviceEInk,
        )
    }

    suspend fun loadWidgetDataFromRepositories(
        bookRepository: BookRepository,
        checkpointDao: SyncCheckpointDao,
        playbackSession: PlaybackSession,
        readerPreferencesRepository: ReaderPreferencesRepository,
        loadCover: suspend (Book) -> Bitmap?,
        isEInkDevice: Boolean = false,
    ): ContinueReadingWidgetData {
        val books = bookRepository.observeContinueReading().first()
        val book = books.firstOrNull() ?: return ContinueReadingWidgetData.EMPTY

        val checkpoint = checkpointDao.getCheckpoint(book.id, book.serverId, book.userId)?.toDomain()
        val progressPercent = checkpoint?.progressPercent ?: 0f

        val coverBitmap = loadCover(book)
        val hasAudio = MediaLibraryTreeBuilder.isAudioEligible(book)
        val nowPlaying = playbackSession.nowPlaying.value
        val isPlaying = nowPlaying?.let { it.book.id == book.id && it.isPlaying } ?: false

        val settings = readerPreferencesRepository.settings.first()
        val isEInk = isEInkDevice || settings.eInkOptimized

        return ContinueReadingWidgetData(
            bookId = book.id,
            title = book.title,
            author = book.authors.joinToString(", "),
            coverBitmap = coverBitmap,
            progressPercent = progressPercent.coerceIn(0f, 100f),
            hasAudio = hasAudio,
            isPlaying = isPlaying,
            isEInk = isEInk,
            isEmpty = false,
        )
    }

    /**
     * Requests an update of all active widgets, throttled to at most once every [THROTTLE_MS].
     * Invoked automatically on reading/narration checkpoint saves.
     */
    fun requestUpdate(context: Context) {
        if (!initCompleted.get()) {
            updateRequestedBeforeInit.set(true)
            return
        }
        val appContext = context.applicationContext ?: context
        val now = System.currentTimeMillis()
        val elapsed = now - lastUpdateMs.get()
        if (elapsed >= THROTTLE_MS) {
            lastUpdateMs.set(now)
            synchronized(updateLock) {
                pendingJob?.cancel()
                pendingJob = updaterScope.launch {
                    updateAll(appContext)
                }
            }
        } else {
            synchronized(updateLock) {
                if (pendingJob?.isActive == true) return
                pendingJob = updaterScope.launch {
                    delay(THROTTLE_MS - elapsed)
                    lastUpdateMs.set(System.currentTimeMillis())
                    updateAll(appContext)
                }
            }
        }
    }

    suspend fun updateImmediately(context: Context) {
        lastUpdateMs.set(System.currentTimeMillis())
        synchronized(updateLock) {
            pendingJob?.cancel()
        }
        updateAll(context.applicationContext)
    }

    private suspend fun updateAll(context: Context) {
        try {
            ContinueReadingWidget().updateAll(context)
        } catch (_: Throwable) {
            // Widget host might be unavailable or uninstalled; ignore
        }
    }

    suspend fun loadCoverBitmap(context: Context, book: Book): Bitmap? = withContext(Dispatchers.IO) {
        val coversDir = File(context.filesDir, "covers")
        val extensions = listOf("jpg", "png", "webp", "jpeg")
        val file = extensions.map { File(coversDir, "${book.id}.$it") }.firstOrNull { it.isFile && it.length() > 0L }
        if (file != null) {
            val decoded = decodeScaledBitmap(file, 160, 240)
            if (decoded != null) return@withContext decoded
        }

        val coverUrl = book.coverUrl
        if (coverUrl != null && coverUrl.startsWith("file://")) {
            val uri = runCatching { coverUrl.toUri() }.getOrNull()
            if (uri?.path != null) {
                val f = File(uri.path!!)
                if (f.isFile && f.length() > 0L) {
                    val decoded = decodeScaledBitmap(f, 160, 240)
                    if (decoded != null) return@withContext decoded
                }
            }
        }

        if (coverUrl.isNullOrBlank() || (!coverUrl.startsWith("http://") && !coverUrl.startsWith("https://"))) {
            return@withContext null
        }

        runCatching {
            val imageLoader = context.imageLoader
            val request = ImageRequest.Builder(context)
                .data(coverUrl)
                .size(160, 240)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    runCatching {
                        val dir = File(context.filesDir, "covers").apply { mkdirs() }
                        val cacheFile = File(dir, "${book.id}.jpg")
                        cacheFile.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                    }
                    bitmap
                } else null
            } else null
        }.getOrNull()
    }

    private fun decodeScaledBitmap(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        return runCatching {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            var sampleSize = 1
            while (boundsOptions.outWidth / (sampleSize * 2) >= targetWidth &&
                boundsOptions.outHeight / (sampleSize * 2) >= targetHeight
            ) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        }.getOrNull()
    }
}
