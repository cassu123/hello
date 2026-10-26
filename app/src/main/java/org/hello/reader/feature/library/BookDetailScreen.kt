package org.hello.reader.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.hello.reader.R
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.ReadingStatusItem
import org.hello.reader.core.model.isLocal
import org.hello.reader.ui.components.FormatBadge
import org.hello.reader.ui.components.ProgressLine
import org.hello.reader.ui.components.label
import org.hello.reader.ui.theme.Spacing
import org.hello.reader.ui.theme.blend
import kotlin.math.absoluteValue

/** The book page: cover, metadata, and the one primary action that names what will happen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpen: (Book) -> Unit,
    onOpenAudio: (Book) -> Unit = onOpen,
    onOpenSeries: (String) -> Unit = {},
    onOpenCollection: (String) -> Unit = {},
    onSearchCreator: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val book = state.byId[bookId]

    val isReadaloud = book?.let {
        it.hasReadaloud || it.availableFormats().contains(BookMediaType.READALOUD) || it.mediaType == BookMediaType.READALOUD
    } ?: false

    if (book == null) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back)) }
            if (!state.isLoading) {
                Text(
                    stringResource(R.string.book_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(Spacing.Gutter),
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }
    val progress = if (book.isToRead) 0f else if (book.isRead) 1f else (state.progress[book.id] ?: 0f)
    val seriesEntries by viewModel.observeSeriesForBook(book.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val collections by viewModel.observeCollectionsForBook(book.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val downloads by viewModel.observeDownloadsForBook(book.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val readingStatuses by viewModel.availableReadingStatuses.collectAsStateWithLifecycle()
    var showFormatSheet by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(book.id) {
        viewModel.loadReadingStatuses()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
        // Tonal backdrop behind the cover.
        Box(
            Modifier
                .fillMaxWidth()
                .background(blend(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground, 0.05f))
                .statusBarsPadding()
                .padding(bottom = Spacing.L + 12.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = Spacing.XXS)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                CoverImage(
                    book = book,
                    shape = RoundedCornerShape(6.dp),
                    titleSize = 20.sp,
                    modifier = Modifier
                        .width(152.dp)
                        .aspectRatio(COVER_ASPECT)
                        .shadow(12.dp, RoundedCornerShape(6.dp), clip = false),
                )

                val availableFormats = book.availableFormats()
                val canRead = availableFormats.any { it == BookMediaType.EBOOK_ONLY || it == BookMediaType.READALOUD }
                val canListen = availableFormats.any { it == BookMediaType.AUDIO_ONLY || it == BookMediaType.READALOUD }

                if (canRead && canListen) {
                    Surface(
                        modifier = Modifier.offset(y = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 2.dp,
                        tonalElevation = 2.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                onClick = {
                                    if (isReadaloud) {
                                        onOpen(book.copy(mediaType = BookMediaType.READALOUD))
                                    } else {
                                        viewModel.setPreferredMediaType(book, BookMediaType.EBOOK_ONLY)
                                        onOpen(book.copy(mediaType = BookMediaType.EBOOK_ONLY))
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = if (!isReadaloud && book.mediaType == BookMediaType.EBOOK_ONLY) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text(stringResource(R.string.action_read), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Surface(
                                onClick = {
                                    if (isReadaloud) {
                                        onOpenAudio(book.copy(mediaType = BookMediaType.READALOUD))
                                    } else {
                                        viewModel.setPreferredMediaType(book, BookMediaType.AUDIO_ONLY)
                                        onOpen(book.copy(mediaType = BookMediaType.AUDIO_ONLY))
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = if (!isReadaloud && book.mediaType == BookMediaType.AUDIO_ONLY) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Outlined.Headphones, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text(stringResource(R.string.action_listen), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = Spacing.Gutter)) {
            Text(
                book.title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.Gutter),
            )

            if (book.authors.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.XXS),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.by_author_prefix),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    book.authors.forEachIndexed { idx, author ->
                        Text(
                            text = author + (if (idx < book.authors.size - 1) "," else ""),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier.clickable {
                                viewModel.setQuery(author)
                                onSearchCreator(author)
                            },
                        )
                    }
                }
            }
            if (book.narrators.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.XXS),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.narrated_by_prefix),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    book.narrators.forEachIndexed { idx, narrator ->
                        Text(
                            text = narrator + (if (idx < book.narrators.size - 1) "," else ""),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier.clickable {
                                viewModel.setQuery(narrator)
                                onSearchCreator(narrator)
                            },
                        )
                    }
                }
            }

            seriesEntries.forEach { entry ->
                val seriesLabel = entry.positionNumber?.let {
                    stringResource(R.string.book_position_of_series, it, entry.seriesName)
                } ?: entry.seriesName
                Text(
                    text = seriesLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.XXS)
                        .clickable { onOpenSeries(entry.seriesUuid) },
                )
            }

            if (collections.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.XS),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.XS, Alignment.CenterHorizontally),
                ) {
                    items(collections, key = { it.uuid }) { coll ->
                        AssistChip(
                            onClick = { onOpenCollection(coll.uuid) },
                            label = { Text(coll.name, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.CollectionsBookmark,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.S),
                horizontalArrangement = Arrangement.spacedBy(Spacing.XS, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val displayBook = if (isReadaloud) book.copy(mediaType = BookMediaType.READALOUD) else book
                FormatBadge(displayBook.mediaType, text = formatDetail(displayBook))
                ReadingStatusDropdown(
                    book = book,
                    progress = progress,
                    availableStatuses = readingStatuses,
                    onSelectStatus = { newStatus ->
                        viewModel.updateReadingStatus(book, newStatus) { error ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(error)
                            }
                        }
                    },
                )
                if (book.isLocal) FormatBadge(book.mediaType, text = stringResource(R.string.on_this_device))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.S),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.meta_rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StarRatingBar(
                    rating = book.rating,
                    onRatingChanged = { newRating ->
                        viewModel.setRating(book, newRating)
                    },
                )
            }

            if (progress > 0f) {
                Column(Modifier.padding(top = Spacing.Gutter)) {
                    ProgressLine(progress)
                    Text(
                        if (progress >= 1f) stringResource(R.string.finished) else stringResource(R.string.percent_read, LibraryFormatters.percent(progress)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Row(
                Modifier.padding(top = Spacing.Gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val target = if (isReadaloud) book.copy(mediaType = BookMediaType.READALOUD) else book
                        onOpen(target)
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    val target = if (isReadaloud) book.copy(mediaType = BookMediaType.READALOUD) else book
                    if (target.mediaType == BookMediaType.EBOOK_ONLY) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(Spacing.XS))
                    Text(stringResource(primaryActionLabelRes(target, progress)))
                }
                if (!book.isLocal) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(48.dp),
                    ) {
                        DownloadButton(
                            book = book,
                            onDownload = {
                                val available = book.availableFormats()
                                if (available.size > 1) {
                                    showFormatSheet = true
                                } else {
                                    viewModel.download(book, available.firstOrNull() ?: book.mediaType)
                                }
                            },
                            onCancel = { viewModel.cancelDownload(book) },
                        )
                    }
                }
            }

            if (showFormatSheet) {
                DownloadFormatSheet(
                    book = book,
                    onSelectFormat = { format ->
                        viewModel.download(book, format)
                        showFormatSheet = false
                    },
                    onDismiss = { showFormatSheet = false },
                )
            }

            if (!book.isLocal) {
                Spacer(Modifier.height(Spacing.M))
                DownloadsSection(
                    book = book,
                    downloads = downloads,
                    onDownload = { format: BookMediaType -> viewModel.download(book, format) },
                    onCancel = { viewModel.cancelDownload(book) },
                    onRemove = { format: BookMediaType -> viewModel.removeDownload(book, format) },
                )
            }

            book.description?.takeIf { it.isNotBlank() }?.let { raw ->
                Spacer(Modifier.height(Spacing.M))
                Description(raw)
            }

            Column(Modifier.padding(top = Spacing.L)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (book.totalChapters > 0) MetaRow(stringResource(R.string.meta_chapters), book.totalChapters.toString())
                if (book.durationSeconds > 0) MetaRow(stringResource(R.string.meta_length), LibraryFormatters.position(book.durationSeconds * 1000L))
                book.publicationDate?.takeIf { it.isNotBlank() }?.let { date -> MetaRow(stringResource(R.string.meta_published), date) }
                book.publisher?.takeIf { it.isNotBlank() }?.let { pub -> MetaRow(stringResource(R.string.meta_publisher), pub) }
                book.language?.takeIf { it.isNotBlank() }?.let { lang -> MetaRow(stringResource(R.string.meta_language), lang) }
                book.isbn?.takeIf { it.isNotBlank() }?.let { isbn -> MetaRow(stringResource(R.string.meta_isbn), isbn) }
                book.externalRatings?.takeIf { it.isNotBlank() }?.let { ratings -> MetaRow(stringResource(R.string.meta_external_ratings), ratings) }
                if (book.tags.isNotEmpty()) MetaRow(stringResource(R.string.meta_tags), book.tags.joinToString())
                MetaRow(stringResource(R.string.meta_source), stringResource(if (book.isLocal) R.string.source_imported else R.string.source_storyteller))
            }
            Spacer(Modifier.height(Spacing.XL))
        }
    }
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
}
}

@Composable
private fun ReadingStatusDropdown(
    book: Book,
    progress: Float,
    availableStatuses: List<ReadingStatusItem>,
    onSelectStatus: (ReadingStatusItem) -> Unit,
) {
    val statuses = availableStatuses.filter { !it.name.equals("none", ignoreCase = true) }
        .ifEmpty { ReadingStatusItem.DEFAULT_STATUSES }
    val canEdit = availableStatuses.isNotEmpty() && (!book.isLocal || availableStatuses.any { it.isServerStatus })
    var expanded by remember { mutableStateOf(false) }

    val defaultStatus = statuses.firstOrNull { it.name.equals("to_read", ignoreCase = true) || it.uuid == "to-read" }
        ?: statuses.first()

    val currentLabel = book.readingStatusLabel
        ?: statuses.firstOrNull { it.name.equals(book.readingStatus, ignoreCase = true) }?.label
        ?: if (progress > 0f && !book.isRead) {
            statuses.firstOrNull { it.name.equals("reading", ignoreCase = true) }?.label ?: stringResource(R.string.shelf_reading)
        } else {
            defaultStatus.label
        }

    Box {
        Surface(
            onClick = { if (canEdit) expanded = true },
            enabled = canEdit,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (canEdit) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            statuses.forEach { status ->
                val isSelected = if (book.readingStatus == null) {
                    if (progress > 0f && !book.isRead) {
                        status.name.equals("reading", ignoreCase = true) || status.uuid == "reading"
                    } else {
                        status.name.equals(defaultStatus.name, ignoreCase = true) || status.uuid == defaultStatus.uuid
                    }
                } else {
                    book.readingStatus.equals(status.name, ignoreCase = true) ||
                        book.statusUuid == status.uuid ||
                        book.readingStatusLabel.equals(status.label, ignoreCase = true)
                }
                DropdownMenuItem(
                    text = { Text(status.label) },
                    onClick = {
                        onSelectStatus(status)
                        expanded = false
                    },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun StarRatingBar(
    rating: Float?,
    onRatingChanged: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentRating = rating ?: 0f
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 1..5) {
            val isFilled = currentRating >= i
            IconButton(
                onClick = {
                    if (currentRating.toInt() == i && (currentRating - i).absoluteValue < 0.25f) {
                        onRatingChanged(null)
                    } else {
                        onRatingChanged(i.toFloat())
                    }
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "$i stars",
                    tint = if (isFilled) Color(0xFFFFB800) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun formatDetail(book: Book): String {
    val minutes = book.durationSeconds / 60
    val length = when {
        minutes >= 60 -> stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
        minutes > 0 -> stringResource(R.string.duration_minutes, minutes)
        else -> null
    }
    return listOfNotNull(book.mediaType.label(), length.takeIf { book.mediaType != BookMediaType.EBOOK_ONLY }).joinToString(" · ")
}

@androidx.annotation.StringRes
internal fun primaryActionLabelRes(book: Book, progress: Float): Int {
    val started = progress > 0f && progress < 1f
    return when (book.mediaType) {
        BookMediaType.READALOUD -> if (started) R.string.action_continue else R.string.action_read_and_listen
        BookMediaType.AUDIO_ONLY -> if (started) R.string.action_resume else R.string.action_listen
        BookMediaType.EBOOK_ONLY -> if (started) R.string.action_continue_reading else R.string.action_read
    }
}

private fun decodeHtmlEntities(input: String): String {
    return input
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
}

fun formatDescription(raw: String): AnnotatedString {
    var text = raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("(?i)<p>"), "")
        .replace(Regex("(?i)<b>|<strong>"), "**")
        .replace(Regex("(?i)</b>|</strong>"), "**")
        .replace(Regex("(?i)<i>|<em>"), "*")
        .replace(Regex("(?i)</i>|</em>"), "*")
        .replace(Regex("<[^>]+>"), "")

    text = decodeHtmlEntities(text).trim()

    val builder = AnnotatedString.Builder()
    val pattern = Regex("(\\*\\*(.*?)\\*\\*)|(\\*([^*]+)\\*)|(_([^_]+)_)|(\\[([^\\]]+)\\]\\([^)]+\\))|(`([^`]+)`)")
    var lastIndex = 0

    for (match in pattern.findAll(text)) {
        if (match.range.first > lastIndex) {
            builder.append(text.substring(lastIndex, match.range.first))
        }
        val bold = match.groups[2]?.value
        val italicStar = match.groups[4]?.value
        val italicUnderscore = match.groups[6]?.value
        val linkText = match.groups[8]?.value
        val codeText = match.groups[10]?.value

        when {
            bold != null -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(bold)
                builder.pop()
            }
            italicStar != null -> {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                builder.append(italicStar)
                builder.pop()
            }
            italicUnderscore != null -> {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                builder.append(italicUnderscore)
                builder.pop()
            }
            linkText != null -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Medium))
                builder.append(linkText)
                builder.pop()
            }
            codeText != null -> {
                builder.append(codeText)
            }
            else -> {
                builder.append(match.value)
            }
        }
        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) {
        builder.append(text.substring(lastIndex))
    }

    return builder.toAnnotatedString()
}

@Composable
private fun Description(raw: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val formatted = remember(raw) {
        formatDescription(raw)
    }
    Column(Modifier.padding(top = Spacing.L)) {
        Text(
            text = formatted,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (formatted.length > 220) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.S)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(2f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DownloadsSection(
    book: Book,
    downloads: List<org.hello.reader.core.database.entity.BookDownloadEntity>,
    onDownload: (BookMediaType) -> Unit,
    onCancel: () -> Unit,
    onRemove: (BookMediaType) -> Unit,
) {
    val availableFormats = book.availableFormats()
    if (availableFormats.isEmpty()) return

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Text(
            stringResource(R.string.downloads_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        availableFormats.forEach { format ->
            val download = downloads.firstOrNull { it.format == format }
            val isDownloaded = download?.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED
            val isDownloading = download?.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADING ||
                download?.downloadStatus == org.hello.reader.core.model.DownloadStatus.QUEUED

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Icon(
                    when (format) {
                        BookMediaType.READALOUD -> Icons.Outlined.Headphones
                        BookMediaType.AUDIO_ONLY -> Icons.Outlined.Headphones
                        BookMediaType.EBOOK_ONLY -> Icons.AutoMirrored.Outlined.MenuBook
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        format.label(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        when {
                            isDownloaded -> {
                                val sizeStr = if (download.sizeBytes > 0L) {
                                    " · " + LibraryFormatters.formatBytes(download.sizeBytes)
                                } else ""
                                stringResource(R.string.available_offline) + sizeStr
                            }
                            isDownloading -> {
                                if (download?.downloadStatus == org.hello.reader.core.model.DownloadStatus.QUEUED) {
                                    stringResource(R.string.queued)
                                } else {
                                    stringResource(R.string.downloading) + " ${LibraryFormatters.percent(download?.downloadProgress ?: 0f)}"
                                }
                            }
                            else -> stringResource(R.string.not_downloaded)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    isDownloaded -> {
                        TextButton(onClick = { onRemove(format) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_remove_download), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    isDownloading -> {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                    else -> {
                        Button(
                            onClick = { onDownload(format) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.reader_download_action))
                        }
                    }
                }
            }
        }
    }
}

