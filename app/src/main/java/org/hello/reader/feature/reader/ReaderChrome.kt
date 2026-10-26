package org.hello.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HeadsetOff
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.hello.reader.core.model.Book
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hello.reader.core.engine.reader.ReaderMode
import org.hello.reader.core.engine.reader.ReaderStateMachine
import org.hello.reader.feature.library.CoverImage
import org.hello.reader.feature.library.LibraryFormatters
import org.hello.reader.ui.components.ProgressLine
import org.hello.reader.ui.theme.Literata
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing
import java.util.Locale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.ui.components.chapterLabel
import org.hello.reader.ui.components.rememberLabels

/** Height of [ReaderTopBar] below the status bar; Now playing pads its content by this much. */
internal val ReaderTopBarHeight = 64.dp

/** Playback state and actions shared by the reader toolbar and Now playing. */
internal data class AudioControls(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val sleepRemainingMs: Long?,
    val skipBackSeconds: Int,
    val skipForwardSeconds: Int,
    val onTogglePlayPause: () -> Unit,
    val onSeekTo: (Long) -> Unit,
    val onSkipBy: (Long) -> Unit,
    val onSpeedChange: (Float) -> Unit,
    val onStartSleepTimer: (Int) -> Unit,
    val onCancelSleepTimer: () -> Unit,
    /** Chapter starts as fractions of the book, drawn as ticks on the scrubber. */
    val chapterTicks: List<Float> = emptyList(),
    /** Narration left in the current chapter, when chapters have timing. */
    val chapterRemainingMs: Long? = null,
    val onPreviousChapter: (() -> Unit)? = null,
    val onNextChapter: (() -> Unit)? = null,
    val onOpenChapters: (() -> Unit)? = null,
    val sleepAtChapterEnd: Boolean = false,
    /** Null when the current chapter has no known end. */
    val onSleepAtChapterEnd: (() -> Unit)? = null,
)

/** Back, centered title + chapter, then notebook, Aa and bookmark. [listening] is null for books without narration. */
@Composable
internal fun ReaderTopBar(
    title: String,
    subtitle: String,
    listening: Boolean?,
    isBookmarked: Boolean,
    canBookmark: Boolean,
    showTextTools: Boolean,
    onBack: () -> Unit,
    onToggleListening: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onToggleAudioOnlyView: (() -> Unit)? = null,
    isAudioOnlyView: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ReaderTopBarHeight - 1.dp)
                    .padding(horizontal = Spacing.XXS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back_to_library))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.XXS),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onToggleAudioOnlyView != null) {
                    IconButton(onClick = onToggleAudioOnlyView) {
                        Icon(
                            if (isAudioOnlyView) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.Headphones,
                            contentDescription = stringResource(if (isAudioOnlyView) R.string.switch_to_read_along else R.string.switch_to_audio_player),
                            tint = if (isAudioOnlyView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (listening != null) {
                    IconButton(onClick = onToggleListening) {
                        Icon(
                            if (listening) Icons.Outlined.HeadsetOff else Icons.Outlined.Headphones,
                            contentDescription = stringResource(if (listening) R.string.reader_stop_listening else R.string.reader_listen_along),
                        )
                    }
                }
                if (showTextTools) {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search_in_book))
                    }
                    IconButton(onClick = onOpenNotebook) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = stringResource(R.string.reader_contents_and_notes))
                    }
                    val textAndTheme = stringResource(R.string.reader_text_and_theme)
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(role = Role.Button, onClick = onOpenSettings)
                            .semantics { contentDescription = textAndTheme },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    }
                    IconButton(onClick = onToggleBookmark, enabled = canBookmark) {
                        Icon(
                            if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = stringResource(if (isBookmarked) R.string.reader_remove_bookmark else R.string.reader_bookmark_page),
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * Floating bottom toolbar: progress, labels and, when narration is running, transport controls.
 * [onListen] adds a "Listen" button for readalouds being read silently.
 */
@Composable
internal fun ReaderToolbar(
    status: ReaderStatus,
    audio: AudioControls?,
    onListen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onToggleAudioOnlyView: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = Spacing.S, vertical = Spacing.S),
    ) {
        Column(Modifier.padding(start = Spacing.M, end = Spacing.M, top = Spacing.S, bottom = Spacing.XS)) {
            if (audio != null) {
                AudioScrubber(audio)
            } else {
                ProgressLine(status.progress, Modifier.padding(vertical = 6.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.XXS, bottom = Spacing.XXS),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(status.percentLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                audio != null -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.XXS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpeedPill(audio)
                    Spacer(Modifier.weight(1f))
                    SkipButton(audio.skipBackSeconds, forward = false, onClick = { audio.onSkipBy(-audio.skipBackSeconds * 1000L) })
                    Spacer(Modifier.width(Spacing.XS))
                    PlayPauseButton(audio.isPlaying, audio.onTogglePlayPause, size = 52.dp, corner = 18.dp)
                    Spacer(Modifier.width(Spacing.XS))
                    SkipButton(audio.skipForwardSeconds, forward = true, onClick = { audio.onSkipBy(audio.skipForwardSeconds * 1000L) })
                    Spacer(Modifier.weight(1f))
                    if (onToggleAudioOnlyView != null) {
                        IconButton(onClick = onToggleAudioOnlyView) {
                            Icon(Icons.Outlined.Headphones, contentDescription = stringResource(R.string.switch_to_audio_player))
                        }
                    } else {
                        SleepButton(audio)
                    }
                }
                onListen != null -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.XXS, bottom = Spacing.XXS)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(role = Role.Button, onClick = onListen),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.XS))
                    Text(stringResource(R.string.reader_listen_from_here), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Docked mini-player card inside the reader for [ReaderMode.ReadaloudSynced].
 * Shows cover thumbnail, chapter title, page/position, inline scrubber,
 * transport controls (speed, rewind 15s, play/pause, forward 30s) and percentage.
 */
@Composable
internal fun ReaderMiniPlayerCard(
    book: Book,
    status: ReaderStatus,
    audio: AudioControls,
    onOpenAudioOnlyView: () -> Unit,
    modifier: Modifier = Modifier,
    isEInk: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (isEInk) 0.dp else 4.dp,
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = Spacing.S, vertical = Spacing.S),
    ) {
        Column(Modifier.padding(start = Spacing.M, end = Spacing.M, top = Spacing.S, bottom = Spacing.XS)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.switch_to_audio_player), onClick = onOpenAudioOnlyView),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                CoverImage(
                    book = book,
                    shape = RoundedCornerShape(4.dp),
                    titleSize = 8.sp,
                    modifier = Modifier
                        .width(36.dp)
                        .aspectRatio(2f / 3f),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        status.chapter,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val posLabel = when {
                        status.pageLabel.isNotBlank() && status.detail.isNotBlank() ->
                            "${status.pageLabel} · ${status.detail}"
                        status.pageLabel.isNotBlank() -> status.pageLabel
                        else -> status.detail
                    }
                    if (posLabel.isNotBlank()) {
                        Text(
                            posLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    status.percentLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(Modifier.padding(top = 2.dp)) {
                AudioScrubber(audio)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.XXS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpeedPill(audio)
                Spacer(Modifier.weight(1f))
                SkipButton(audio.skipBackSeconds, forward = false, onClick = { audio.onSkipBy(-audio.skipBackSeconds * 1000L) })
                Spacer(Modifier.width(Spacing.XS))
                PlayPauseButton(audio.isPlaying, audio.onTogglePlayPause, size = 52.dp, corner = 18.dp)
                Spacer(Modifier.width(Spacing.XS))
                SkipButton(audio.skipForwardSeconds, forward = true, onClick = { audio.onSkipBy(audio.skipForwardSeconds * 1000L) })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenAudioOnlyView) {
                    Icon(Icons.Outlined.Headphones, contentDescription = stringResource(R.string.switch_to_audio_player))
                }
            }
        }
    }
}

/**
 * Bottom-half controls for tabletop mode (half-opened horizontal fold).
 * Features cover art thumbnail, chapter / book titles, scrubber, and large transport controls.
 */
@Composable
internal fun TabletopAudioControls(
    status: ReaderStatus,
    audio: AudioControls?,
    bookTitle: String?,
    chapterTitle: String?,
    modifier: Modifier = Modifier,
    onListen: (() -> Unit)? = null,
    onToggleAudioOnlyView: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.L, vertical = Spacing.M),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = bookTitle.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!chapterTitle.isNullOrBlank()) {
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
            ) {
                if (audio != null) {
                    AudioScrubber(audio)
                } else {
                    ProgressLine(status.progress, Modifier.padding(vertical = 6.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.XXS),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(status.percentLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (audio != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 480.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SpeedPill(audio)
                    SkipButton(audio.skipBackSeconds, forward = false, onClick = { audio.onSkipBy(-audio.skipBackSeconds * 1000L) })
                    PlayPauseButton(audio.isPlaying, audio.onTogglePlayPause, size = 64.dp, corner = 22.dp)
                    SkipButton(audio.skipForwardSeconds, forward = true, onClick = { audio.onSkipBy(audio.skipForwardSeconds * 1000L) })
                    if (onToggleAudioOnlyView != null) {
                        IconButton(onClick = onToggleAudioOnlyView) {
                            Icon(Icons.Outlined.Headphones, contentDescription = stringResource(R.string.switch_to_audio_player))
                        }
                    } else {
                        SleepButton(audio)
                    }
                }
            } else if (onListen != null) {
                Row(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(role = Role.Button, onClick = onListen),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.Headphones, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(Spacing.S))
                    Text(stringResource(R.string.reader_listen_from_here), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

/** Chapter and progress, faint, under the text while the chrome is hidden. */
@Composable
internal fun QuietFooter(status: ReaderStatus, modifier: Modifier = Modifier) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.L, vertical = Spacing.S),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(status.chapter, style = MaterialTheme.typography.labelMedium, color = faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(Spacing.S))
        Text(
            listOf(status.detail, status.percentLabel).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = faint,
            maxLines = 1,
        )
    }
}

@Composable
internal fun QuietProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(progress.coerceIn(0f, 1f))
            .height(2.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
    )
}

/** Audio-only player screen: cover, title, scrubber, transport, and Speed/Sleep tiles. */
@Composable
internal fun NowPlayingContent(
    mode: ReaderMode.ListeningOnly,
    controls: AudioControls,
    modifier: Modifier = Modifier,
) = NowPlayingContent(
    book = mode.book,
    chapterIndex = mode.chapterIndex,
    chapterTitle = mode.chapterTitle,
    controls = controls,
    onSwitchToReading = null,
    modifier = modifier,
)

@Composable
internal fun NowPlayingContent(
    book: org.hello.reader.core.model.Book,
    chapterIndex: Int,
    chapterTitle: String?,
    controls: AudioControls,
    modifier: Modifier = Modifier,
    onSwitchToReading: (() -> Unit)? = null,
) {
    val foldableInfo = rememberFoldableLayoutInfo()
    val isTabletop = foldableInfo.isTabletop

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val maxW = maxWidth
        val maxH = maxHeight
        val isWideOrLandscape = maxW >= 600.dp || maxW > maxH * 1.1f

        when {
            isTabletop -> {
                TabletopSplitLayout(
                    foldingFeature = foldableInfo.foldingFeature,
                    topContent = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = Spacing.L, vertical = Spacing.S),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (onSwitchToReading != null) {
                                    SwitchToReadingButton(
                                        onClick = onSwitchToReading,
                                        modifier = Modifier.padding(bottom = Spacing.S),
                                    )
                                }
                                CoverImage(
                                    book = book,
                                    shape = RoundedCornerShape(Radius.Card),
                                    titleSize = 22.sp,
                                    modifier = Modifier
                                        .fillMaxHeight(0.85f)
                                        .aspectRatio(1f)
                                        .widthIn(max = 280.dp),
                                )
                            }
                        }
                    },
                    bottomContent = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = Spacing.L, vertical = Spacing.S),
                            contentAlignment = Alignment.Center,
                        ) {
                            ControlsSection(
                                book = book,
                                chapterIndex = chapterIndex,
                                chapterTitle = chapterTitle,
                                controls = controls,
                                isTabletop = true,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            isWideOrLandscape -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.L),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = Spacing.M, vertical = Spacing.M),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (onSwitchToReading != null) {
                                SwitchToReadingButton(
                                    onClick = onSwitchToReading,
                                    modifier = Modifier.padding(bottom = Spacing.M),
                                )
                            }
                            val coverMaxH = (maxH - (if (onSwitchToReading != null) 96.dp else 48.dp)).coerceAtLeast(160.dp)
                            val coverMaxW = minOf(360.dp, maxW * 0.42f, coverMaxH)
                            CoverImage(
                                book = book,
                                shape = RoundedCornerShape(Radius.Card),
                                titleSize = 28.sp,
                                modifier = Modifier
                                    .size(coverMaxW)
                                    .aspectRatio(1f),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .padding(horizontal = Spacing.M, vertical = Spacing.M),
                        contentAlignment = Alignment.Center,
                    ) {
                        ControlsSection(
                            book = book,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapterTitle,
                            controls = controls,
                            isTabletop = false,
                        )
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.L),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(0.1f))
                    if (onSwitchToReading != null) {
                        SwitchToReadingButton(
                            onClick = onSwitchToReading,
                            modifier = Modifier.padding(bottom = Spacing.S),
                        )
                    }
                    val maxCoverH = (maxH - 380.dp).coerceAtLeast(140.dp)
                    val maxCoverW = minOf(maxW * 0.75f, 320.dp, maxCoverH)
                    CoverImage(
                        book = book,
                        shape = RoundedCornerShape(Radius.Card),
                        titleSize = 24.sp,
                        modifier = Modifier
                            .size(maxCoverW)
                            .aspectRatio(1f),
                    )
                    Spacer(Modifier.height(Spacing.M))
                    ControlsSection(
                        book = book,
                        chapterIndex = chapterIndex,
                        chapterTitle = chapterTitle,
                        controls = controls,
                        isTabletop = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchToReadingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.M, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.switch_to_read_along), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ControlsSection(
    book: org.hello.reader.core.model.Book,
    chapterIndex: Int,
    chapterTitle: String?,
    controls: AudioControls,
    isTabletop: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val byline = listOfNotNull(
                book.authors.joinToString().takeIf { it.isNotBlank() },
                chapterLabel(chapterIndex, chapterTitle),
            ).joinToString(" · ")
            Text(byline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (book.narrators.isNotEmpty()) {
                Text(
                    stringResource(R.string.narrated_by, book.narrators.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.M))
        AudioScrubber(controls)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(LibraryFormatters.position(controls.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            controls.chapterRemainingMs?.let { left ->
                Text(
                    rememberLabels().timeLeftInChapter(left),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "−" + LibraryFormatters.position((controls.durationMs - controls.positionMs).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.M))
        // Five buttons must still fit a 360dp screen, so they shrink when the chapter buttons show.
        val withChapters = controls.onPreviousChapter != null || controls.onNextChapter != null
        val skipSize = if (withChapters) 48.dp else 56.dp
        val skipIcon = if (withChapters) 28.dp else 32.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (withChapters) Spacing.XS else Spacing.L),
        ) {
            controls.onPreviousChapter?.let { ChapterButton(forward = false, onClick = it) }
            SkipButton(controls.skipBackSeconds, forward = false, size = skipSize, iconSize = skipIcon, onClick = { controls.onSkipBy(-controls.skipBackSeconds * 1000L) })
            PlayPauseButton(controls.isPlaying, controls.onTogglePlayPause, size = if (withChapters) 72.dp else 76.dp, corner = 26.dp)
            SkipButton(controls.skipForwardSeconds, forward = true, size = skipSize, iconSize = skipIcon, onClick = { controls.onSkipBy(controls.skipForwardSeconds * 1000L) })
            controls.onNextChapter?.let { ChapterButton(forward = true, onClick = it) }
        }
        Spacer(Modifier.height(Spacing.M))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = if (isTabletop) 0.dp else Spacing.L),
            horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
        ) {
            SpeedPill(controls, tile = true, modifier = Modifier.weight(1f))
            SleepButton(controls, tile = true, modifier = Modifier.weight(1f))
            controls.onOpenChapters?.let { open ->
                ControlChip(tile = true, onClick = open, description = stringResource(R.string.chapters), modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.chapters), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChapterButton(forward: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            if (forward) Icons.Outlined.SkipNext else Icons.Outlined.SkipPrevious,
            contentDescription = stringResource(if (forward) R.string.next_chapter else R.string.previous_chapter),
            modifier = Modifier.size(28.dp),
        )
    }
}

/** Seeks on release, so dragging doesn't fire a seek per frame. */
@Composable
private fun AudioScrubber(audio: AudioControls) {
    val maxMs = audio.durationMs.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val description = stringResource(R.string.playback_position)
    Slider(
        value = dragging ?: audio.positionMs.coerceIn(0L, maxMs).toFloat(),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { audio.onSeekTo(it.toLong()) }
            dragging = null
        },
        valueRange = 0f..maxMs.toFloat(),
        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .chapterTicks(audio.chapterTicks, MaterialTheme.colorScheme.surfaceContainer)
            .semantics { contentDescription = description },
    )
}

/** Small notches cut into the track where chapters start. */
private fun Modifier.chapterTicks(fractions: List<Float>, color: androidx.compose.ui.graphics.Color): Modifier =
    if (fractions.isEmpty()) {
        this
    } else {
        drawWithContent {
            drawContent()
            // The track is inset by half the thumb's width at each end.
            val inset = 2.dp.toPx()
            val usable = size.width - 2 * inset
            val tick = Size(2.dp.toPx(), 8.dp.toPx())
            fractions.forEach { f ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset + usable * f - tick.width / 2, (size.height - tick.height) / 2),
                    size = tick,
                    cornerRadius = CornerRadius(tick.width / 2),
                )
            }
        }
    }

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit, size: Dp, corner: Dp) {
    val description = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/** Circular-arrow skip with the seconds printed inside; forward mirrors the arrow. */
@Composable
private fun SkipButton(seconds: Int, forward: Boolean, onClick: () -> Unit, size: Dp = 44.dp, iconSize: Dp = 26.dp) {
    val description = pluralStringResource(if (forward) R.plurals.skip_forward_seconds else R.plurals.skip_back_seconds, seconds, seconds)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Replay,
            contentDescription = null,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            seconds.toString(),
            fontSize = (iconSize.value * 0.30f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = iconSize * 0.12f),
        )
    }
}

internal val SpeedPresets = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

internal fun formatSpeed(speed: Float): String =
    LibraryFormatters.speed(speed, trimTrailingZeros = true)

@Composable
private fun SpeedPill(audio: AudioControls, modifier: Modifier = Modifier, tile: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ControlChip(
            tile = tile,
            onClick = { open = true },
            description = stringResource(R.string.playback_speed_description, formatSpeed(audio.speed)),
        ) {
            Text(formatSpeed(audio.speed), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
            if (tile) Text(stringResource(R.string.speed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.speed_slower)) },
                onClick = { audio.onSpeedChange(audio.speed - ReaderStateMachine.SPEED_STEP) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.speed_faster)) },
                onClick = { audio.onSpeedChange(audio.speed + ReaderStateMachine.SPEED_STEP) },
            )
            HorizontalDivider()
            SpeedPresets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Text(
                            formatSpeed(preset),
                            fontWeight = if (preset == audio.speed) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        audio.onSpeedChange(preset)
                        open = false
                    },
                )
            }
        }
    }
}

internal val SleepPresetsMinutes = listOf(15, 30, 60)

@Composable
private fun SleepButton(audio: AudioControls, modifier: Modifier = Modifier, tile: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val remaining = audio.sleepRemainingMs
    Box(modifier) {
        ControlChip(
            tile = tile,
            onClick = { open = true },
            description = when {
                remaining != null -> stringResource(R.string.sleep_timer_remaining, LibraryFormatters.position(remaining))
                audio.sleepAtChapterEnd -> stringResource(R.string.sleep_timer_chapter_end)
                else -> stringResource(R.string.sleep_timer)
            },
            active = remaining != null || audio.sleepAtChapterEnd,
        ) {
            if (remaining != null) {
                Text(LibraryFormatters.position(remaining), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
            } else {
                Icon(Icons.Outlined.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (tile) Text(stringResource(R.string.sleep), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SleepPresetsMinutes.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(pluralStringResource(R.plurals.sleep_minutes, minutes, minutes)) },
                    onClick = {
                        audio.onStartSleepTimer(minutes)
                        open = false
                    },
                )
            }
            audio.onSleepAtChapterEnd?.let { sleepAtEnd ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.sleep_end_of_chapter),
                            fontWeight = if (audio.sleepAtChapterEnd) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        sleepAtEnd()
                        open = false
                    },
                )
            }
            if (remaining != null || audio.sleepAtChapterEnd) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_turn_off)) },
                    onClick = {
                        audio.onCancelSleepTimer()
                        open = false
                    },
                )
            }
        }
    }
}

/** Small sunken pill (toolbar) or a taller tile with a caption (Now playing). */
@Composable
private fun ControlChip(
    tile: Boolean,
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    if (tile) {
        Column(
            modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(Radius.Card - 2.dp))
                .background(container)
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    } else {
        Row(
            modifier
                .height(40.dp)
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(container)
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
                .padding(horizontal = Spacing.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) { content() }
    }
}
