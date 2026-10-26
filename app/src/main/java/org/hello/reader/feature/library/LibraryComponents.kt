package org.hello.reader.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import coil.compose.AsyncImage
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.isLocal
import org.hello.reader.ui.components.FormatBadge
import org.hello.reader.ui.components.ProgressLine
import org.hello.reader.ui.components.hasAudio
import org.hello.reader.ui.components.icon
import org.hello.reader.ui.components.label
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.core.model.BookCollection
import org.hello.reader.core.model.Series
import org.hello.reader.ui.components.rememberLabels

internal const val COVER_ASPECT = 2f / 3f

/** "Homer · 42%" style secondary line under a cover. */
internal fun Book.byline(): String = authors.firstOrNull().orEmpty()

/** The single large card at the top of Home for the most recently opened book. */
@Composable
fun ContinueHeroCard(
    item: ContinueItem,
    onResume: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = item.book
    Surface(
        onClick = onOpenDetails,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(Radius.Card),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.M), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            CoverImage(
                book = book,
                titleSize = 15.sp,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(COVER_ASPECT),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        book.mediaType.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (book.isLocal) stringResource(R.string.joined_with_dot, book.mediaType.label(), stringResource(R.string.on_this_device)) else book.mediaType.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    book.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    book.byline(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.S))
                ProgressLine(item.progress)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(
                        LibraryFormatters.percent(item.progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    item.remainingMs?.let {
                        Text(
                            rememberLabels().timeLeft(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .padding(top = Spacing.S)
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    if (book.mediaType.hasAudio) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_play))
                    } else {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.XS))
                        Text(stringResource(R.string.action_continue))
                    }
                }
            }
        }
    }
}

/** Compact in-progress row: small cover, title, format and percent, thin progress line. */
@Composable
fun InProgressItem(item: ContinueItem, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .width(172.dp)
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CoverImage(
            item.book,
            titleSize = 7.sp,
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(COVER_ASPECT),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.joined_with_dot, item.book.mediaType.label(), LibraryFormatters.percent(item.progress)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            ProgressLine(item.progress, height = 2.dp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Grid / shelf cell: 2:3 cover with badge and progress, title and byline underneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridItem(
    book: Book,
    progress: Float,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    showBadge: Boolean = true,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onSelectToggle: () -> Unit = {},
    onLongClick: () -> Unit = {},
    hasDownloadedReadaloud: Boolean = false,
    onOpenRead: (() -> Unit)? = null,
    onOpenListen: (() -> Unit)? = null,
) {
    val effectiveProgress = if (book.isToRead) 0f else if (book.isRead) 1f else progress
    Column(
        modifier
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectToggle()
                    } else {
                        onOpen()
                    }
                },
                onLongClick = {
                    if (isSelectionMode) {
                        onSelectToggle()
                    } else {
                        onLongClick()
                    }
                },
            )
            .semantics(mergeDescendants = true) {},
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.Card))
                    } else Modifier
                ),
        ) {
            CoverImage(book, modifier = Modifier.matchParentSize(), titleSize = 12.sp)
            if (showBadge && book.mediaType.hasAudio && !hasDownloadedReadaloud) {
                FormatBadge(
                    book.mediaType,
                    onCover = true,
                    text = if (book.mediaType == BookMediaType.AUDIO_ONLY) stringResource(R.string.format_audio_short) else book.mediaType.label(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 9.dp),
                )
            }
            if (effectiveProgress > 0f && effectiveProgress < 1f) {
                ProgressLine(
                    effectiveProgress,
                    track = Color.Black.copy(alpha = 0.28f),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (hasDownloadedReadaloud && !isSelectionMode && onOpenRead != null && onOpenListen != null) {
                val isEInk = org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = onOpenRead,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isEInk) 1f else 0.92f),
                        shadowElevation = if (isEInk) 0.dp else 2.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isEInk) 1f else 0.6f)),
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = stringResource(R.string.action_read),
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Surface(
                        onClick = onOpenListen,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isEInk) 1f else 0.92f),
                        shadowElevation = if (isEInk) 0.dp else 2.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isEInk) 1f else 0.6f)),
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Outlined.Headphones,
                                contentDescription = stringResource(R.string.action_listen),
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape,
                        )
                        .border(1.5.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.state_selected),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.XS),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    effectiveProgress >= 1f -> stringResource(R.string.finished)
                    effectiveProgress > 0f -> stringResource(R.string.joined_with_dot, LibraryFormatters.percent(effectiveProgress), book.byline())
                    else -> book.byline()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            AvailabilityGlyph(book)
        }
    }
}

/** Tiny trailing glyph: cloud when the book isn't on the device yet, phone for imports. */
@Composable
private fun AvailabilityGlyph(book: Book) {
    val (icon, description) = when {
        book.isLocal -> Icons.Outlined.PhoneAndroid to stringResource(R.string.on_this_device)
        book.downloadStatus == DownloadStatus.DOWNLOADED -> return
        book.downloadStatus == DownloadStatus.DOWNLOADING || book.downloadStatus == DownloadStatus.QUEUED ->
            Icons.Outlined.CloudQueue to stringResource(R.string.downloading)
        book.downloadStatus == DownloadStatus.FAILED -> Icons.Outlined.ErrorOutline to stringResource(R.string.download_failed)
        else -> Icons.Outlined.CloudOff to stringResource(R.string.not_downloaded)
    }
    Icon(
        icon,
        contentDescription = description,
        modifier = Modifier.size(13.dp),
        tint = if (book.downloadStatus == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListRow(
    book: Book,
    progress: Float,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onSelectToggle: () -> Unit = {},
    onLongClick: () -> Unit = {},
    hasDownloadedReadaloud: Boolean = false,
    onOpenRead: (() -> Unit)? = null,
    onOpenListen: (() -> Unit)? = null,
) {
    val effectiveProgress = if (book.isToRead) 0f else if (book.isRead) 1f else progress
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectToggle()
                    } else {
                        onOpen()
                    }
                },
                onLongClick = {
                    if (isSelectionMode) {
                        onSelectToggle()
                    } else {
                        onLongClick()
                    }
                },
            )
            .padding(horizontal = Spacing.Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.state_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        CoverImage(
            book = book,
            titleSize = 8.sp,
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(COVER_ASPECT),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    book.byline().takeIf { it.isNotEmpty() },
                    book.mediaType.label(),
                    when {
                        effectiveProgress >= 1f -> stringResource(R.string.finished)
                        effectiveProgress > 0f -> LibraryFormatters.percent(effectiveProgress)
                        else -> null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!isSelectionMode) {
            if (hasDownloadedReadaloud && onOpenRead != null && onOpenListen != null) {
                val isEInk = org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = onOpenRead,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isEInk) 1f else 0.92f),
                        shadowElevation = if (isEInk) 0.dp else 1.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isEInk) 1f else 0.6f)),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = stringResource(R.string.action_read),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Surface(
                        onClick = onOpenListen,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isEInk) 1f else 0.92f),
                        shadowElevation = if (isEInk) 0.dp else 1.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isEInk) 1f else 0.6f)),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Outlined.Headphones,
                                contentDescription = stringResource(R.string.action_listen),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            } else {
                DownloadButton(book, onDownload, onCancelDownload)
            }
        }
    }
}

/** Download control for list rows and the book page. */
@Composable
fun DownloadButton(book: Book, onDownload: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    if (book.isLocal) {
        Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.PhoneAndroid,
                contentDescription = stringResource(R.string.on_this_device),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        return
    }
    when (val status = book.downloadStatus) {
        DownloadStatus.NOT_DOWNLOADED -> IconButton(onClick = onDownload, modifier = modifier) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.download_book, book.title))
        }
        DownloadStatus.FAILED -> IconButton(onClick = onDownload, modifier = modifier) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.download_failed_retry, book.title),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> IconButton(onClick = onCancel, modifier = modifier) {
            val label = if (status == DownloadStatus.QUEUED) stringResource(R.string.queued) else LibraryFormatters.percent(book.downloadProgress)
            val description = stringResource(R.string.downloading_book, book.title, label)
            Box(
                Modifier.semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                if (status == DownloadStatus.QUEUED || book.downloadProgress <= 0f) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(
                        progress = { book.downloadProgress },
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        DownloadStatus.DOWNLOADED -> Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.DownloadDone,
                contentDescription = stringResource(R.string.available_offline),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Bottom sheet offering all 3 Storyteller formats: Readaloud (synced), E-book (text), Audiobook (audio).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadFormatSheet(
    book: Book,
    onSelectFormat: (BookMediaType) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.Gutter)
                .padding(bottom = Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
            ) {
                CoverImage(
                    book = book,
                    titleSize = 10.sp,
                    modifier = Modifier
                        .width(44.dp)
                        .aspectRatio(COVER_ASPECT),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.download_format_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        book.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val available = book.availableFormats()

            // 1. Readaloud (Synced text + narration)
            if (BookMediaType.READALOUD in available) {
                FormatOptionTile(
                    icon = Icons.Outlined.Headphones,
                    title = stringResource(R.string.format_readaloud),
                    description = stringResource(R.string.format_readaloud_desc),
                    badge = stringResource(R.string.action_read_and_listen),
                    isSelected = book.mediaType == BookMediaType.READALOUD,
                    onClick = {
                        onSelectFormat(BookMediaType.READALOUD)
                        onDismiss()
                    },
                )
            }

            // 2. E-book (Text only)
            if (BookMediaType.EBOOK_ONLY in available) {
                FormatOptionTile(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = stringResource(R.string.format_ebook),
                    description = stringResource(R.string.format_ebook_desc),
                    badge = stringResource(R.string.action_read),
                    isSelected = book.mediaType == BookMediaType.EBOOK_ONLY,
                    onClick = {
                        onSelectFormat(BookMediaType.EBOOK_ONLY)
                        onDismiss()
                    },
                )
            }

            // 3. Audiobook (Audio narration only)
            if (BookMediaType.AUDIO_ONLY in available) {
                FormatOptionTile(
                    icon = Icons.Outlined.Audiotrack,
                    title = stringResource(R.string.format_audiobook),
                    description = stringResource(R.string.format_audiobook_desc),
                    badge = stringResource(R.string.action_listen),
                    isSelected = book.mediaType == BookMediaType.AUDIO_ONLY,
                    onClick = {
                        onSelectFormat(BookMediaType.AUDIO_ONLY)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun FormatOptionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    badge: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                    Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SeriesCard(
    item: SeriesWithCovers,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.Card),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.S)) {
            StackedCovers(item.coverUrls, item.series.name)
            Spacer(Modifier.height(Spacing.S))
            Text(
                item.series.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.book_count, item.series.totalBooks, item.series.totalBooks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StackedCovers(coverUrls: List<String>, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            coverUrls.size >= 3 -> {
                CoverPreview(
                    url = coverUrls[2],
                    title = title,
                    modifier = Modifier
                        .offset(x = (-16).dp, y = (-4).dp)
                        .graphicsLayer(rotationZ = -6f)
                        .shadow(4.dp, RoundedCornerShape(4.dp)),
                )
                CoverPreview(
                    url = coverUrls[1],
                    title = title,
                    modifier = Modifier
                        .offset(x = 16.dp, y = (-2).dp)
                        .graphicsLayer(rotationZ = 5f)
                        .shadow(6.dp, RoundedCornerShape(4.dp)),
                )
                CoverPreview(
                    url = coverUrls[0],
                    title = title,
                    modifier = Modifier
                        .shadow(8.dp, RoundedCornerShape(4.dp)),
                )
            }
            coverUrls.size == 2 -> {
                CoverPreview(
                    url = coverUrls[1],
                    title = title,
                    modifier = Modifier
                        .offset(x = 12.dp, y = (-4).dp)
                        .graphicsLayer(rotationZ = 4f)
                        .shadow(4.dp, RoundedCornerShape(4.dp)),
                )
                CoverPreview(
                    url = coverUrls[0],
                    title = title,
                    modifier = Modifier
                        .offset(x = (-6).dp)
                        .shadow(8.dp, RoundedCornerShape(4.dp)),
                )
            }
            coverUrls.size == 1 -> {
                CoverPreview(
                    url = coverUrls[0],
                    title = title,
                    modifier = Modifier.shadow(6.dp, RoundedCornerShape(4.dp)),
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .aspectRatio(COVER_ASPECT)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.CollectionsBookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CoverPreview(url: String, title: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
            .width(72.dp)
            .aspectRatio(COVER_ASPECT),
    ) {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun CollectionCard(
    collection: BookCollection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.Card),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.M)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                val color = parseCardColor(collection.color, MaterialTheme.colorScheme.primaryContainer)
                Surface(
                    shape = CircleShape,
                    color = color,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.CollectionsBookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        collection.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pluralStringResource(R.plurals.book_count, collection.totalBooks, collection.totalBooks),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            collection.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(Spacing.S))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun parseCardColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(hex.toColorInt())
    } catch (_: Exception) {
        fallback
    }
}

/**
 * Bottom sheet for downloading selected books in multiple formats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkDownloadSheet(
    books: List<Book>,
    onDownload: (Set<BookMediaType>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasAnyReadaloud = remember(books) { books.any { it.hasReadaloud } }
    val hasAnyEbook = remember(books) { books.any { it.hasEbook } }
    val hasAnyAudio = remember(books) { books.any { it.hasAudiobook } }

    var selectedReadaloud by remember(hasAnyReadaloud) { mutableStateOf(hasAnyReadaloud) }
    var selectedEbook by remember(hasAnyEbook, hasAnyReadaloud) { mutableStateOf(!hasAnyReadaloud && hasAnyEbook) }
    var selectedAudio by remember(hasAnyAudio) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.Gutter)
                .padding(bottom = Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Text(
                stringResource(R.string.bulk_download_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                pluralStringResource(R.plurals.bulk_download_subtitle, books.size, books.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Readaloud Option
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasAnyReadaloud) { selectedReadaloud = !selectedReadaloud }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Checkbox(
                    checked = selectedReadaloud,
                    onCheckedChange = { selectedReadaloud = it },
                    enabled = hasAnyReadaloud,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.format_readaloud),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = if (hasAnyReadaloud) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Text(
                        stringResource(R.string.format_readaloud_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // E-book Option
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasAnyEbook) { selectedEbook = !selectedEbook }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Checkbox(
                    checked = selectedEbook,
                    onCheckedChange = { selectedEbook = it },
                    enabled = hasAnyEbook,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.format_ebook),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = if (hasAnyEbook) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Text(
                        stringResource(R.string.format_ebook_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Audiobook Option
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasAnyAudio) { selectedAudio = !selectedAudio }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Checkbox(
                    checked = selectedAudio,
                    onCheckedChange = { selectedAudio = it },
                    enabled = hasAnyAudio,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.format_audiobook),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = if (hasAnyAudio) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Text(
                        stringResource(R.string.format_audiobook_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.S))

            val anySelected = selectedReadaloud || selectedEbook || selectedAudio
            Button(
                onClick = {
                    val formats = buildSet {
                        if (selectedReadaloud) add(BookMediaType.READALOUD)
                        if (selectedEbook) add(BookMediaType.EBOOK_ONLY)
                        if (selectedAudio) add(BookMediaType.AUDIO_ONLY)
                    }
                    onDownload(formats)
                },
                enabled = anySelected,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(stringResource(R.string.reader_download_action))
            }
        }
    }
}

/**
 * Confirmation dialog before removing downloaded files for bulk selected books.
 */
@Composable
fun BulkRemoveConfirmDialog(
    bookCount: Int,
    freedBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_downloads_confirm_title)) },
        text = {
            Text(
                if (freedBytes > 0L) {
                    pluralStringResource(R.plurals.remove_downloads_confirm_body, bookCount, bookCount, LibraryFormatters.formatBytes(freedBytes))
                } else {
                    pluralStringResource(R.plurals.remove_downloads_confirm_body_no_size, bookCount, bookCount)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_remove_download), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
