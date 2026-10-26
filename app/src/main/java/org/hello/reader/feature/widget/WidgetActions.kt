package org.hello.reader.feature.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.hello.reader.app.HelloApplication
import org.hello.reader.core.engine.audio.LibraryPlaybackStarter

/**
 * Glance action callback toggling audio playback when the user taps play/pause on the widget.
 * If audio is already loaded or playing (in the reader or background), it toggles play/pause.
 * If nothing is active, it starts background playback for the active profile's continue-reading book.
 */
class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val app = context.applicationContext as? HelloApplication ?: return
        val playbackSession = app.container.playbackSession
        if (playbackSession.hasActivePlayback()) {
            playbackSession.togglePlayPause()
        } else {
            startContinueReadingPlayback(context, app)
        }
        ContinueReadingWidgetUpdater.updateImmediately(context)
    }

    private suspend fun startContinueReadingPlayback(context: Context, app: HelloApplication) {
        val container = app.container
        val books = container.bookRepository.observeContinueReading().first()
        val book = books.firstOrNull() ?: return

        val prepared = LibraryPlaybackStarter.preparePlayback(
            context = context,
            container = container,
            bookId = book.id,
        ) ?: return

        withContext(Dispatchers.Main) {
            container.playbackSession.startFromLibrary(
                handoff = prepared.handoff,
                tracks = prepared.tracks,
                startMs = prepared.startMs,
                artworkUri = prepared.coverUri?.toString(),
            )
        }
    }
}
