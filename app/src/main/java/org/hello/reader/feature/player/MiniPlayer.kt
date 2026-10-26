package org.hello.reader.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.hello.reader.app.appContainer
import org.hello.reader.core.engine.audio.NowPlaying
import org.hello.reader.core.engine.audio.PlaybackSession
import org.hello.reader.feature.library.CoverImage
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.hello.reader.R

class MiniPlayerViewModel(private val session: PlaybackSession) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying?> = session.nowPlaying

    fun togglePlayPause() = session.togglePlayPause()

    fun skipForward() = session.skipBy(30_000L)

    fun stop() = session.stop()

    companion object {
        val Factory = viewModelFactory {
            initializer { MiniPlayerViewModel(appContainer.playbackSession) }
        }
    }
}

/** The book still playing after its reader closed; shown above the bottom bar. */
@Composable
fun MiniPlayerHost(
    onOpen: (bookId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MiniPlayerViewModel = viewModel(factory = MiniPlayerViewModel.Factory),
) {
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    nowPlaying?.let { playing ->
        MiniPlayer(
            nowPlaying = playing,
            onOpen = { onOpen(playing.book.id) },
            onTogglePlayPause = viewModel::togglePlayPause,
            onSkipForward = viewModel::skipForward,
            onClose = viewModel::stop,
            modifier = modifier,
        )
    }
}

@Composable
fun MiniPlayer(
    nowPlaying: NowPlaying,
    onOpen: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    isEInk: Boolean = org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(Radius.Card),
        border = if (isEInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = if (isEInk) 0.dp else 2.dp,
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .padding(horizontal = Spacing.XS, vertical = Spacing.XXS)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -18f) onOpen()
                }
            },
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.open_player), onClick = onOpen)
                    .padding(start = Spacing.S, end = Spacing.XXS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                CoverImage(
                    book = nowPlaying.book,
                    titleSize = 6.sp,
                    modifier = Modifier
                        .width(34.dp)
                        .aspectRatio(2f / 3f),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        nowPlaying.book.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = nowPlaying.chapterTitle?.takeIf { it.isNotBlank() }
                        ?: nowPlaying.book.authors.joinToString().takeIf { it.isNotBlank() }
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val skipForwardLabel = stringResource(R.string.forward_30s)
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button, onClick = onSkipForward)
                        .semantics { contentDescription = skipForwardLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Replay,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { scaleX = -1f },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "30",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                val playPause = stringResource(if (nowPlaying.isPlaying) R.string.action_pause else R.string.action_play)
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(role = Role.Button, onClick = onTogglePlayPause)
                        .semantics { contentDescription = playPause },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (nowPlaying.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.stop_player))
                }
            }
            val progressFraction = if (isEInk) {
                // Refresh-friendly coarse progress line on e-ink (5% steps)
                (Math.round(nowPlaying.progress * 20f) / 20f).coerceIn(0f, 1f)
            } else {
                nowPlaying.progress
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progressFraction)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
