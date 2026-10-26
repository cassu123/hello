package org.hello.reader.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.hello.reader.R
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.SeriesBookItem
import org.hello.reader.core.model.isLocal
import org.hello.reader.ui.components.ProgressLine
import org.hello.reader.ui.theme.Spacing

@Composable
fun CollectionDetailScreen(
    collectionUuid: String,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenBookDetails: (Book) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val collDetail by viewModel.observeCollectionDetail(collectionUuid).collectAsStateWithLifecycle(initialValue = null)
    val libraryState by viewModel.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var bookForFormatDownload by remember { mutableStateOf<Book?>(null) }
    var showBulkRemoveDialog by remember { mutableStateOf(false) }
    var showBulkDownloadSheet by remember { mutableStateOf(false) }
    var bulkFreedBytes by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = libraryState.isSelectionMode) {
        viewModel.clearSelection()
    }

    if (collDetail == null) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        return
    }

    val detail = collDetail!!

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = Spacing.XL),
        ) {
            item(key = "header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.Gutter),
                ) {
                    if (libraryState.isSelectionMode) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.XS),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                        ) {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                            }
                            Text(
                                pluralStringResource(R.plurals.selection_count, libraryState.selectedBookIds.size, libraryState.selectedBookIds.size),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            val allCollBookIds = buildList {
                                detail.seriesGroups.forEach { group -> addAll(group.books.map { it.book.id }) }
                                addAll(detail.standaloneBooks.map { it.id })
                            }
                            TextButton(onClick = { viewModel.selectAllBooks(allCollBookIds) }) {
                                Text(
                                    stringResource(
                                        if (libraryState.selectedBookIds.containsAll(allCollBookIds) && allCollBookIds.isNotEmpty()) {
                                            R.string.deselect_all
                                        } else {
                                            R.string.select_all
                                        }
                                    )
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { showBulkDownloadSheet = true },
                                enabled = libraryState.selectedBookIds.isNotEmpty(),
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.bulk_download_action))
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        bulkFreedBytes = viewModel.calculateSelectedSize()
                                        showBulkRemoveDialog = true
                                    }
                                },
                                enabled = libraryState.selectedBookIds.isNotEmpty(),
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_remove_downloads))
                            }
                        }
                    } else {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.padding(top = Spacing.XS),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }

                    Row(
                        modifier = Modifier.padding(top = Spacing.S),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = parseCardColor(detail.collection.color, MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.size(36.dp),
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
                        Text(
                            text = detail.collection.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Text(
                        text = pluralStringResource(R.plurals.book_count, detail.collection.totalBooks, detail.collection.totalBooks),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.XXS),
                    )

                    detail.collection.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = Spacing.S),
                        )
                        if (desc.length > 140) {
                            TextButton(
                                onClick = { expanded = !expanded },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
                            }
                        }
                    }

                    Spacer(Modifier.height(Spacing.L))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(Spacing.S))
                }
            }

            // Series groups
            detail.seriesGroups.forEach { group ->
                item(key = "series_header_${group.series.uuid}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSeries(group.series.uuid) }
                            .padding(horizontal = Spacing.Gutter, vertical = Spacing.S),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.series.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = pluralStringResource(R.plurals.book_count, group.books.size, group.books.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = stringResource(R.string.series),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                items(group.books, key = { "series_${group.series.uuid}_${it.book.id}" }) { item ->
                    val progress = libraryState.progress[item.book.id] ?: 0f
                    CollectionBookRow(
                        book = item.book,
                        positionLabel = item.positionLabel,
                        progress = progress,
                        onClick = { onOpenBookDetails(item.book) },
                        isSelected = item.book.id in libraryState.selectedBookIds,
                        isSelectionMode = libraryState.isSelectionMode,
                        onSelectToggle = { viewModel.toggleBookSelection(item.book.id) },
                        onLongClick = { viewModel.toggleBookSelection(item.book.id) },
                        onDownload = {
                            if (item.book.isLocal) {
                                viewModel.download(item.book)
                            } else {
                                val available = item.book.availableFormats()
                                if (available.size > 1) {
                                    bookForFormatDownload = item.book
                                } else {
                                    viewModel.download(item.book, available.firstOrNull() ?: item.book.mediaType)
                                }
                            }
                        },
                        onCancelDownload = { viewModel.cancelDownload(item.book) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = Spacing.Gutter + 48.dp + Spacing.S),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            // Standalone books
            if (detail.standaloneBooks.isNotEmpty()) {
                item(key = "standalone_header") {
                    Text(
                        text = stringResource(R.string.standalone_books),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.M),
                    )
                }

                items(detail.standaloneBooks, key = { "standalone_${it.id}" }) { book ->
                    val progress = libraryState.progress[book.id] ?: 0f
                    CollectionBookRow(
                        book = book,
                        positionLabel = null,
                        progress = progress,
                        onClick = { onOpenBookDetails(book) },
                        isSelected = book.id in libraryState.selectedBookIds,
                        isSelectionMode = libraryState.isSelectionMode,
                        onSelectToggle = { viewModel.toggleBookSelection(book.id) },
                        onLongClick = { viewModel.toggleBookSelection(book.id) },
                        onDownload = {
                            if (book.isLocal) {
                                viewModel.download(book)
                            } else {
                                val available = book.availableFormats()
                                if (available.size > 1) {
                                    bookForFormatDownload = book
                                } else {
                                    viewModel.download(book, available.firstOrNull() ?: book.mediaType)
                                }
                            }
                        },
                        onCancelDownload = { viewModel.cancelDownload(book) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = Spacing.Gutter + 48.dp + Spacing.S),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }

        bookForFormatDownload?.let { target ->
            DownloadFormatSheet(
                book = target,
                onSelectFormat = { format ->
                    viewModel.download(target, format)
                    bookForFormatDownload = null
                },
                onDismiss = { bookForFormatDownload = null },
            )
        }

        if (showBulkRemoveDialog) {
            BulkRemoveConfirmDialog(
                bookCount = libraryState.selectedBookIds.size,
                freedBytes = bulkFreedBytes,
                onConfirm = {
                    viewModel.removeSelectedDownloads { _, _ -> }
                    showBulkRemoveDialog = false
                },
                onDismiss = { showBulkRemoveDialog = false },
            )
        }

        if (showBulkDownloadSheet) {
            val allCollBooks = buildList {
                detail.seriesGroups.forEach { group -> addAll(group.books.map { it.book }) }
                addAll(detail.standaloneBooks)
            }.filter { it.id in libraryState.selectedBookIds }
            BulkDownloadSheet(
                books = allCollBooks,
                onDownload = { formats ->
                    viewModel.bulkDownload(formats)
                    showBulkDownloadSheet = false
                },
                onDismiss = { showBulkDownloadSheet = false },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionBookRow(
    book: Book,
    positionLabel: String?,
    progress: Float,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onSelectToggle: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectToggle()
                    } else {
                        onClick()
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
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .padding(end = Spacing.S)
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
            shape = RoundedCornerShape(4.dp),
            titleSize = 10.sp,
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(COVER_ASPECT),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.S),
        ) {
            positionLabel?.let { pos ->
                Text(
                    text = pos,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val author = book.authors.firstOrNull()
            if (author != null) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress > 0f) {
                Spacer(Modifier.height(4.dp))
                ProgressLine(progress, modifier = Modifier.fillMaxWidth(0.7f))
            }
        }

        if (!isSelectionMode && !book.isLocal) {
            DownloadButton(
                book = book,
                onDownload = onDownload,
                onCancel = onCancelDownload,
            )
        }
    }
}
