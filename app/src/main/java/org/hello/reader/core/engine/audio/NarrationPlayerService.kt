package org.hello.reader.core.engine.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.hello.reader.R
import org.hello.reader.app.HelloApplication
import org.hello.reader.app.MainActivity
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.SyncCheckpoint

/**
 * Android Auto and lock-screen MediaLibraryService.
 * Exposes the library browse tree for in-car browsing and responds to transport controls.
 *
 * Media items are mapped directly to local/cached media files, with cover artwork
 * served via FileProvider content URIs so Android Auto head units can display covers
 * without failing on authenticated Storyteller server URLs.
 *
 * Standard next/previous buttons are mapped via [ForwardingPlayer] to [PlaybackSession]
 * chapter navigation, ensuring chapter skips work across lock-screen, car, and Bluetooth/headsets.
 */
@OptIn(UnstableApi::class)
class NarrationPlayerService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var pausedTimestampMs: Long? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val customLayoutButtons by lazy {
        val rewindButton = CommandButton.Builder()
            .setDisplayName(getString(R.string.rewind_30s))
            .setIconResId(R.drawable.ic_replay_30)
            .setSessionCommand(COMMAND_REWIND_30)
            .setEnabled(true)
            .build()

        val forwardButton = CommandButton.Builder()
            .setDisplayName(getString(R.string.forward_30s))
            .setIconResId(R.drawable.ic_forward_30)
            .setSessionCommand(COMMAND_FORWARD_30)
            .setEnabled(true)
            .build()

        listOf(rewindButton, forwardButton)
    }

    private val callback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(MediaLibraryTreeBuilder.buildRootItem(this@NarrationPlayerService), params),
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId == MediaLibraryTreeBuilder.ROOT_ID) {
                return Futures.immediateFuture(
                    LibraryResult.ofItem(MediaLibraryTreeBuilder.buildRootItem(this@NarrationPlayerService), null),
                )
            }
            val category = MediaLibraryTreeBuilder.buildCategoryItem(this@NarrationPlayerService, mediaId)
            if (category != null) {
                return Futures.immediateFuture(LibraryResult.ofItem(category, null))
            }
            if (mediaId.startsWith(MediaLibraryTreeBuilder.MEDIA_ID_BOOK_PREFIX)) {
                val bookId = mediaId.removePrefix(MediaLibraryTreeBuilder.MEDIA_ID_BOOK_PREFIX)
                val future = SettableFuture.create<LibraryResult<MediaItem>>()
                serviceScope.launch {
                    try {
                        val app = application as? HelloApplication
                        val book = withContext(Dispatchers.IO) {
                            app?.container?.bookRepository?.observeLibrary()?.first()?.find { it.id == bookId }
                        }
                        if (book != null && MediaLibraryTreeBuilder.isAudioEligible(book)) {
                            val coverUri = withContext(Dispatchers.IO) {
                                MediaLibraryTreeBuilder.ensureCoverCached(this@NarrationPlayerService, book.id, book.coverUrl)
                            }
                            if (coverUri != null && coverUri.scheme == "content") {
                                grantUriPermission(browser.packageName, coverUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val item = MediaLibraryTreeBuilder.buildBookItem(book, coverUri)
                            future.set(LibraryResult.ofItem(item, null))
                        } else {
                            future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                        }
                    } catch (e: Throwable) {
                        future.setException(e)
                    }
                }
                return future
            }
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val app = application as? HelloApplication
                    val container = app?.container
                    when (parentId) {
                        MediaLibraryTreeBuilder.ROOT_ID -> {
                            val items = MediaLibraryTreeBuilder.buildCategoryItems(this@NarrationPlayerService)
                            future.set(LibraryResult.ofItemList(items, params))
                        }
                        MediaLibraryTreeBuilder.CATEGORY_CONTINUE_LISTENING -> {
                            val books = withContext(Dispatchers.IO) {
                                container?.bookRepository?.observeContinueReading()?.first().orEmpty()
                            }
                            val audioBooks = MediaLibraryTreeBuilder.filterAudioBooks(books)
                            val items = audioBooks.map { b ->
                                val coverUri = withContext(Dispatchers.IO) {
                                    MediaLibraryTreeBuilder.ensureCoverCached(this@NarrationPlayerService, b.id, b.coverUrl)
                                }
                                if (coverUri != null && coverUri.scheme == "content") {
                                    grantUriPermission(browser.packageName, coverUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                MediaLibraryTreeBuilder.buildBookItem(b, coverUri)
                            }
                            future.set(LibraryResult.ofItemList(items, params))
                        }
                        MediaLibraryTreeBuilder.CATEGORY_DOWNLOADED -> {
                            val books = withContext(Dispatchers.IO) {
                                container?.bookRepository?.observeDownloadedBooks()?.first().orEmpty()
                            }
                            val audioBooks = MediaLibraryTreeBuilder.filterAudioBooks(books)
                            val items = audioBooks.map { b ->
                                val coverUri = withContext(Dispatchers.IO) {
                                    MediaLibraryTreeBuilder.ensureCoverCached(this@NarrationPlayerService, b.id, b.coverUrl)
                                }
                                if (coverUri != null && coverUri.scheme == "content") {
                                    grantUriPermission(browser.packageName, coverUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                MediaLibraryTreeBuilder.buildBookItem(b, coverUri)
                            }
                            future.set(LibraryResult.ofItemList(items, params))
                        }
                        MediaLibraryTreeBuilder.CATEGORY_RECENT -> {
                            val books = withContext(Dispatchers.IO) {
                                container?.bookRepository?.observeLibrary()?.first().orEmpty()
                            }
                            val audioBooks = MediaLibraryTreeBuilder.filterAudioBooks(books)
                                .take(MediaLibraryTreeBuilder.MAX_RECENT_ITEMS)
                            val items = audioBooks.map { b ->
                                val coverUri = withContext(Dispatchers.IO) {
                                    MediaLibraryTreeBuilder.ensureCoverCached(this@NarrationPlayerService, b.id, b.coverUrl)
                                }
                                if (coverUri != null && coverUri.scheme == "content") {
                                    grantUriPermission(browser.packageName, coverUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                MediaLibraryTreeBuilder.buildBookItem(b, coverUri)
                            }
                            future.set(LibraryResult.ofItemList(items, params))
                        }
                        else -> {
                            future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                        }
                    }
                } catch (e: Throwable) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(COMMAND_REWIND_30)
                .add(COMMAND_FORWARD_30)
                .add(COMMAND_PREV_CHAPTER)
                .add(COMMAND_NEXT_CHAPTER)
                .build()

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(customLayoutButtons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            val app = application as? HelloApplication
            val playbackSession = app?.container?.playbackSession
            when (customCommand.customAction) {
                ACTION_REWIND_30 -> playbackSession?.skipBy(-30_000L) ?: run {
                    val p = session.player
                    p.seekTo((p.currentPosition - 30_000L).coerceAtLeast(0L))
                }
                ACTION_FORWARD_30 -> playbackSession?.skipBy(30_000L) ?: run {
                    val p = session.player
                    p.seekTo(p.currentPosition + 30_000L)
                }
                ACTION_PREV_CHAPTER -> playbackSession?.skipToPreviousChapter() ?: run {
                    val p = session.player
                    p.seekTo((p.currentPosition - 30_000L).coerceAtLeast(0L))
                }
                ACTION_NEXT_CHAPTER -> playbackSession?.skipToNextChapter() ?: run {
                    val p = session.player
                    p.seekTo(p.currentPosition + 30_000L)
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val target = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            if (target?.mediaId?.startsWith(MediaLibraryTreeBuilder.MEDIA_ID_BOOK_PREFIX) == true) {
                val bookId = target.mediaId.removePrefix(MediaLibraryTreeBuilder.MEDIA_ID_BOOK_PREFIX)
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                serviceScope.launch {
                    try {
                        val app = application as? HelloApplication
                        val container = app?.container
                        if (container != null) {
                            val prepared = LibraryPlaybackStarter.preparePlayback(
                                context = this@NarrationPlayerService,
                                container = container,
                                bookId = bookId,
                                callingPackage = controller.packageName,
                            )
                            if (prepared != null) {
                                withContext(Dispatchers.Main) {
                                    container.playbackSession.adoptBackgroundPlayback(
                                        handoff = prepared.handoff,
                                        tracks = prepared.tracks,
                                        startMs = prepared.startMs,
                                    )
                                }
                                future.set(
                                    MediaSession.MediaItemsWithStartPosition(
                                        prepared.mediaItems,
                                        prepared.startIndex,
                                        prepared.startPositionMs,
                                    ),
                                )
                                return@launch
                            }
                        }
                        future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
                    } catch (e: Throwable) {
                        future.setException(e)
                    }
                }
                return future
            }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
        }

        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    val app = application as? HelloApplication
                    val container = app?.container
                    val continueBook = withContext(Dispatchers.IO) {
                        container?.bookRepository?.observeContinueReading()?.first()?.firstOrNull {
                            MediaLibraryTreeBuilder.isAudioEligible(it)
                        }
                    }
                    if (continueBook != null && container != null) {
                        val prepared = LibraryPlaybackStarter.preparePlayback(
                            context = this@NarrationPlayerService,
                            container = container,
                            bookId = continueBook.id,
                            callingPackage = controller.packageName,
                        )
                        if (prepared != null) {
                            withContext(Dispatchers.Main) {
                                container.playbackSession.adoptBackgroundPlayback(
                                    handoff = prepared.handoff,
                                    tracks = prepared.tracks,
                                    startMs = prepared.startMs,
                                )
                            }
                            future.set(
                                MediaSession.MediaItemsWithStartPosition(
                                    prepared.mediaItems,
                                    prepared.startIndex,
                                    prepared.startPositionMs,
                                ),
                            )
                            return@launch
                        }
                    }
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                } catch (e: Throwable) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val basePlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(30_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()

        basePlayer.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val now = System.currentTimeMillis()
                    if (isPlaying) {
                        val pausedAt = pausedTimestampMs
                        pausedTimestampMs = null
                        if (pausedAt != null) {
                            val rewound = SmartRewindPolicy.applySmartRewind(
                                currentPositionMs = basePlayer.currentPosition,
                                pauseDurationMs = (now - pausedAt).coerceAtLeast(0L),
                            )
                            if (rewound != basePlayer.currentPosition) {
                                basePlayer.seekTo(rewound)
                            }
                        }
                    } else if (basePlayer.playbackState == Player.STATE_READY) {
                        pausedTimestampMs = now
                    }
                }
            },
        )

        val player = object : ForwardingPlayer(basePlayer) {
            override fun seekToPrevious() {
                val app = application as? HelloApplication
                app?.container?.playbackSession?.skipToPreviousChapter() ?: super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                seekToPrevious()
            }

            override fun seekToNext() {
                val app = application as? HelloApplication
                app?.container?.playbackSession?.skipToNextChapter() ?: super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                seekToNext()
            }

            override fun isCommandAvailable(command: @Player.Command Int): Boolean {
                if (command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) {
                    return true
                }
                return super.isCommandAvailable(command)
            }

            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCustomLayout(customLayoutButtons)
            .setMediaButtonPreferences(customLayoutButtons)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val app = application as? HelloApplication
        val container = app?.container
        serviceScope.launch {
            if (container != null) {
                val settings = container.readerPreferencesRepository.settings.first()
                if (settings.keepPlayingAfterAppClose) {
                    return@launch
                }
            }
            try {
                if (container != null) {
                    withTimeoutOrNull(1500L) {
                        container.playbackSession.persistAndStop()
                    }
                }
            } finally {
                mediaLibrarySession?.run {
                    player.pause()
                    player.release()
                    release()
                }
                mediaLibrarySession = null
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        val container = (application as? HelloApplication)?.container
        val snapshot = container?.playbackSession?.snapshotAndStop()
        if (container != null && snapshot != null) {
            container.applicationScope.launch {
                container.playbackSession.persistCheckpoint(snapshot)
            }
        }
        mediaLibrarySession?.run {
            player.pause()
            player.release()
            release()
        }
        mediaLibrarySession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_REWIND_30 = "org.hello.reader.action.REWIND_30"
        const val ACTION_FORWARD_30 = "org.hello.reader.action.FORWARD_30"
        const val ACTION_PREV_CHAPTER = "org.hello.reader.action.PREV_CHAPTER"
        const val ACTION_NEXT_CHAPTER = "org.hello.reader.action.NEXT_CHAPTER"

        val COMMAND_REWIND_30 = SessionCommand(ACTION_REWIND_30, android.os.Bundle.EMPTY)
        val COMMAND_FORWARD_30 = SessionCommand(ACTION_FORWARD_30, android.os.Bundle.EMPTY)
        val COMMAND_PREV_CHAPTER = SessionCommand(ACTION_PREV_CHAPTER, android.os.Bundle.EMPTY)
        val COMMAND_NEXT_CHAPTER = SessionCommand(ACTION_NEXT_CHAPTER, android.os.Bundle.EMPTY)
    }
}
