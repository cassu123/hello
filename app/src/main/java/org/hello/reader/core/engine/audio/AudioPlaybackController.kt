package org.hello.reader.core.engine.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import org.hello.reader.core.engine.smil.AudioTrackItem

/**
 * Abstraction over Media3 [MediaController] / [NarrationPlayerService] so [org.hello.reader.feature.reader.ReaderViewModel]
 * can drive real background audio narration on device while remaining 100% unit-testable on the JVM.
 */
interface AudioPlaybackController {
    val hasLoadedMedia: Boolean
    fun prepareFile(
        audioFile: File,
        title: String,
        artist: String?,
        artworkUri: String?,
        initialPositionMs: Long,
        playbackSpeed: Float,
    )
    fun prepareFile(
        audioFile: File,
        title: String,
        artist: String?,
        artworkUri: String?,
        initialPositionMs: Long,
        playbackSpeed: Float,
        bookId: String?,
    ) {
        prepareFile(audioFile, title, artist, artworkUri, initialPositionMs, playbackSpeed)
    }
    fun prepareTracks(
        tracks: List<AudioTrackItem>,
        title: String,
        artist: String?,
        artworkUri: String?,
        initialPositionMs: Long,
        playbackSpeed: Float,
        bookId: String? = null,
    ) {
        val first = tracks.firstOrNull() ?: return
        prepareFile(first.file, title, artist, artworkUri, initialPositionMs, playbackSpeed, bookId)
    }
    fun adoptTracks(tracks: List<AudioTrackItem>) {}
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setVolume(volume: Float)
    fun currentPositionMs(): Long?

    /** The media ID of the item the player is on, or null when unknown. */
    fun currentMediaId(): String? = null

    /**
     * Whether the player intends to play (Media3 `playWhenReady`), or null when unknown.
     * Turns false when playback is paused from the notification, lock screen or a headset.
     */
    fun isPlaybackRequested(): Boolean?

    /** Stops playback and unloads the media, keeping the session connection for the next book. */
    fun stop()
    fun release()
}

/**
 * Connects to [NarrationPlayerService] via Media3 [MediaController] for lock-screen,
 * notification, Bluetooth/headset, and background narration playback.
 */
class Media3AudioPlaybackController(context: Context) : AudioPlaybackController {

    private val appContext = context.applicationContext
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingSetup: (() -> Unit)? = null
    private var pendingPlay: Boolean = false
    private var activeTracks: List<AudioTrackItem> = emptyList()

    override var hasLoadedMedia: Boolean = false
        private set

    init {
        connectSession()
    }

    private fun connectSession() {
        runCatching {
            val token = SessionToken(appContext, ComponentName(appContext, NarrationPlayerService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    runCatching {
                        controller = future.get()
                        pendingSetup?.invoke()
                        pendingSetup = null
                        if (pendingPlay) {
                            pendingPlay = false
                            controller?.play()
                        }
                    }
                },
                ContextCompat.getMainExecutor(appContext),
            )
        }
    }

    override fun adoptTracks(tracks: List<AudioTrackItem>) {
        val validTracks = tracks.filter { it.file.isFile }
        hasLoadedMedia = validTracks.isNotEmpty()
        activeTracks = validTracks
    }

    override fun prepareTracks(
        tracks: List<AudioTrackItem>,
        title: String,
        artist: String?,
        artworkUri: String?,
        initialPositionMs: Long,
        playbackSpeed: Float,
        bookId: String?,
    ) {
        val validTracks = tracks.filter { it.file.isFile }
        if (validTracks.isEmpty()) {
            hasLoadedMedia = false
            activeTracks = emptyList()
            return
        }
        hasLoadedMedia = true
        activeTracks = validTracks
        val setupBlock: () -> Unit = {
            controller?.let { player ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .apply {
                        if (!artworkUri.isNullOrBlank()) {
                            setArtworkUri(artworkUri.toUri())
                        }
                    }
                    .build()
                val items = validTracks.mapIndexed { index, track ->
                    MediaItem.Builder()
                        .apply {
                            if (!bookId.isNullOrBlank()) {
                                setMediaId("${PlaybackSession.mediaIdPrefix(bookId)}$index")
                            }
                        }
                        .setUri(Uri.fromFile(track.file))
                        .setMediaMetadata(metadata)
                        .build()
                }
                val (initialIndex, initialLocalPos) = resolveTrackPosition(initialPositionMs)
                player.setMediaItems(items, initialIndex, initialLocalPos)
                player.playbackParameters = PlaybackParameters(playbackSpeed.coerceIn(0.5f, 3.5f), 1.0f)
                player.prepare()
            }
        }
        if (controller != null) {
            setupBlock()
        } else {
            pendingSetup = setupBlock
        }
    }

    override fun prepareFile(
        audioFile: File,
        title: String,
        artist: String?,
        artworkUri: String?,
        initialPositionMs: Long,
        playbackSpeed: Float,
    ) {
        prepareFile(
            audioFile = audioFile,
            title = title,
            artist = artist,
            artworkUri = artworkUri,
            initialPositionMs = initialPositionMs,
            playbackSpeed = playbackSpeed,
            bookId = null,
        )
    }

    override fun prepareFile(
        audioFile: File,
        title: String,
        artist: String?,
        artworkUri: String?,
        initialPositionMs: Long,
        playbackSpeed: Float,
        bookId: String?,
    ) {
        prepareTracks(
            tracks = listOf(AudioTrackItem(audioFile, audioFile.name, 0L, 0L)),
            title = title,
            artist = artist,
            artworkUri = artworkUri,
            initialPositionMs = initialPositionMs,
            playbackSpeed = playbackSpeed,
            bookId = bookId,
        )
    }

    private fun resolveTrackPosition(globalPos: Long): Pair<Int, Long> {
        val tracks = activeTracks
        if (tracks.isEmpty()) return 0 to globalPos.coerceAtLeast(0L)
        val clamped = globalPos.coerceAtLeast(0L)
        val index = tracks.indexOfLast { it.startOffsetMs <= clamped }.coerceAtLeast(0)
        val track = tracks[index]
        val localPos = (clamped - track.startOffsetMs).coerceAtLeast(0L)
        return index to localPos
    }

    override fun play() {
        if (!hasLoadedMedia) return
        val c = controller
        if (c != null) {
            c.play()
        } else {
            pendingPlay = true
        }
    }

    override fun pause() {
        pendingPlay = false
        if (!hasLoadedMedia) return
        controller?.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (!hasLoadedMedia) return
        val player = controller ?: return
        val (index, localPos) = resolveTrackPosition(positionMs)
        player.seekTo(index, localPos)
    }

    override fun setSpeed(speed: Float) {
        controller?.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 3.5f), 1.0f)
    }

    override fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    override fun currentPositionMs(): Long? {
        val player = controller ?: return null
        if (!hasLoadedMedia) return null
        val localPos = player.currentPosition.coerceAtLeast(0L)
        val tracks = activeTracks
        if (tracks.isEmpty()) return localPos
        val itemIndex = player.currentMediaItemIndex
        val track = tracks.getOrNull(itemIndex) ?: tracks.firstOrNull() ?: return localPos
        return track.startOffsetMs + localPos
    }

    override fun currentMediaId(): String? = controller?.currentMediaItem?.mediaId

    override fun isPlaybackRequested(): Boolean? {
        if (!hasLoadedMedia) return null
        return controller?.playWhenReady ?: if (pendingPlay) true else null
    }

    override fun stop() {
        hasLoadedMedia = false
        pendingPlay = false
        activeTracks = emptyList()
        pendingSetup = null
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    override fun release() {
        hasLoadedMedia = false
        pendingPlay = false
        activeTracks = emptyList()
        pendingSetup = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }
}
