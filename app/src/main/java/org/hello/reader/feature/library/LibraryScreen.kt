package org.hello.reader.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.isLocal
import org.hello.reader.ui.components.ScreenTitle
import org.hello.reader.ui.theme.Spacing
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.hello.reader.R

private val GridMinCellWidth = 96.dp
private val GridSpacing = 12.dp

/** As many columns of at least [GridMinCellWidth] as fit inside the screen gutters: 3 on most phones. */
internal fun gridColumnsFor(width: Dp): Int {
    val usable = width - Spacing.Gutter * 2
    return ((usable + GridSpacing) / (GridMinCellWidth + GridSpacing)).toInt().coerceAtLeast(1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenDetails: (Book) -> Unit,
    onAddServer: () -> Unit,
    viewModel: LibraryViewModel,
    onOpenBook: (Book) -> Unit = {},
    onOpenAudio: (Book) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onOpenCollection: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importing by viewModel.importsInProgress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val importLauncher = rememberImportLauncher(viewModel)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var bookForFormatDownload by remember { mutableStateOf<Book?>(null) }
    var showBulkRemoveDialog by remember { mutableStateOf(false) }
    var showBulkDownloadSheet by remember { mutableStateOf(false) }
    var bulkFreedBytes by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = state.isSelectionMode) {
        viewModel.clearSelection()
    }

    LibrarySnackbarEffects(viewModel, state.refreshError, snackbar)

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = gridColumnsFor(maxWidth)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(start = Spacing.Gutter, end = Spacing.Gutter, bottom = Spacing.L),
                    horizontalArrangement = Arrangement.spacedBy(GridSpacing),
                    verticalArrangement = Arrangement.spacedBy(if (state.viewMode == LibraryViewMode.GRID) 20.dp else 0.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.bleed(Spacing.Gutter)) {
                            if (state.isSelectionMode) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Spacing.Gutter, vertical = Spacing.S),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                                ) {
                                    IconButton(onClick = viewModel::clearSelection) {
                                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                                    }
                                    Text(
                                        pluralStringResource(R.plurals.selection_count, state.selectedBookIds.size, state.selectedBookIds.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    val allIds = state.books.map { it.id }
                                    TextButton(onClick = { viewModel.selectAllBooks(allIds) }) {
                                        Text(
                                            stringResource(
                                                if (state.selectedBookIds.containsAll(allIds) && allIds.isNotEmpty()) {
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
                                        enabled = state.selectedBookIds.isNotEmpty(),
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
                                        enabled = state.selectedBookIds.isNotEmpty(),
                                    ) {
                                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_remove_downloads))
                                    }
                                }
                            } else {
                                ScreenTitle(stringResource(R.string.library)) {
                                    IconButton(onClick = {
                                        if (searching) viewModel.setQuery("")
                                        searching = !searching
                                    }) {
                                        Icon(
                                            if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                                            contentDescription = stringResource(if (searching) R.string.close_search else R.string.search),
                                        )
                                    }
                                    ImportAction(importing > 0) { importLauncher.launch(ImportMimeTypes) }
                                }
                            }
                        }
                    }
                    if (searching) {
                        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                            SearchField(state.query, viewModel::setQuery)
                        }
                    }
                    item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryTabRow(
                            selectedTab = state.activeTab,
                            onTabSelected = viewModel::setActiveTab,
                        )
                    }
                    when (state.activeTab) {
                        LibraryTab.BOOKS -> {
                            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                                FilterRow(state, viewModel::setFilter)
                            }
                            item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) {
                                CountSortRow(state, viewModel::setSort, viewModel::setViewMode)
                            }
                            when {
                                state.isLoading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                                state.books.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                    EmptyResults(
                                        state = state,
                                        onClear = {
                                            viewModel.setFilter(LibraryFilter.All)
                                            viewModel.setQuery("")
                                        },
                                        onImport = { importLauncher.launch(ImportMimeTypes) },
                                        onAddServer = onAddServer,
                                    )
                                }
                                state.viewMode == LibraryViewMode.GRID -> items(state.books, key = { it.id }) { book ->
                                    val hasDownloadedReadaloud = book.id in state.downloadedReadaloudIds ||
                                        (book.mediaType == BookMediaType.READALOUD && book.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED && !book.localFilePath.isNullOrBlank())
                                    BookGridItem(
                                        book = book,
                                        progress = state.progress[book.id] ?: 0f,
                                        onOpen = { onOpenDetails(book) },
                                        isSelected = book.id in state.selectedBookIds,
                                        isSelectionMode = state.isSelectionMode,
                                        onSelectToggle = { viewModel.toggleBookSelection(book.id) },
                                        onLongClick = { viewModel.toggleBookSelection(book.id) },
                                        hasDownloadedReadaloud = hasDownloadedReadaloud,
                                        onOpenRead = { onOpenBook(book) },
                                        onOpenListen = { onOpenAudio(book) },
                                    )
                                }
                                else -> items(state.books, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { book ->
                                    val hasDownloadedReadaloud = book.id in state.downloadedReadaloudIds ||
                                        (book.mediaType == BookMediaType.READALOUD && book.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED && !book.localFilePath.isNullOrBlank())
                                    Column(Modifier.bleed(Spacing.Gutter)) {
                                        BookListRow(
                                            book = book,
                                            progress = state.progress[book.id] ?: 0f,
                                            onOpen = { onOpenDetails(book) },
                                            isSelected = book.id in state.selectedBookIds,
                                            isSelectionMode = state.isSelectionMode,
                                            onSelectToggle = { viewModel.toggleBookSelection(book.id) },
                                            onLongClick = { viewModel.toggleBookSelection(book.id) },
                                            hasDownloadedReadaloud = hasDownloadedReadaloud,
                                            onOpenRead = { onOpenBook(book) },
                                            onOpenListen = { onOpenAudio(book) },
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
                                            Modifier.padding(start = Spacing.Gutter + 48.dp + Spacing.S),
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                        )
                                    }
                                }
                            }
                        }
                        LibraryTab.SERIES -> {
                            if (state.series.isEmpty()) {
                                item(key = "empty_series", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        stringResource(R.string.series_empty),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                items(state.series, key = { "series_${it.series.uuid}" }) { seriesItem ->
                                    SeriesCard(
                                        item = seriesItem,
                                        onClick = { onOpenSeries(seriesItem.series.uuid) },
                                    )
                                }
                            }
                        }
                        LibraryTab.COLLECTIONS -> {
                            if (state.collections.isEmpty()) {
                                item(key = "empty_collections", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        stringResource(R.string.collections_empty),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                items(state.collections, key = { "coll_${it.uuid}" }, span = { GridItemSpan(maxLineSpan) }) { coll ->
                                    CollectionCard(
                                        collection = coll,
                                        onClick = { onOpenCollection(coll.uuid) },
                                    )
                                }
                            }
                        }
                    }
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
                bookCount = state.selectedBookIds.size,
                freedBytes = bulkFreedBytes,
                onConfirm = {
                    viewModel.removeSelectedDownloads { removedCount, freedBytes ->
                        coroutineScope.launch {
                            snackbar.showSnackbar(
                                context.resources.getQuantityString(
                                    R.plurals.removed_downloads_count,
                                    removedCount,
                                    removedCount,
                                    LibraryFormatters.formatBytes(freedBytes),
                                )
                            )
                        }
                    }
                    showBulkRemoveDialog = false
                },
                onDismiss = { showBulkRemoveDialog = false },
            )
        }
        if (showBulkDownloadSheet) {
            val selectedBooks = state.books.filter { it.id in state.selectedBookIds }.ifEmpty {
                state.byId.values.filter { it.id in state.selectedBookIds }
            }
            BulkDownloadSheet(
                books = selectedBooks,
                onDownload = { formats ->
                    viewModel.bulkDownload(formats)
                    showBulkDownloadSheet = false
                },
                onDismiss = { showBulkDownloadSheet = false },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LibraryTabRow(
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.XS, bottom = Spacing.XXS),
        horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        LibraryTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            FilterChip(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                label = {
                    Text(
                        stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.XS)
            .focusRequester(focus),
    )
}

@Composable
private fun FilterRow(state: LibraryUiState, onFilter: (LibraryFilter) -> Unit) {
    val filters = LibraryFilter.fixed +
        state.readingStatuses +
        state.tags.map { LibraryFilter.Tag(it) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.Gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
        modifier = Modifier
            .bleed(Spacing.Gutter)
            .padding(top = Spacing.S),
    ) {
        items(
            filters,
            key = {
                when (it) {
                    is LibraryFilter.Tag -> "tag:${it.name}"
                    is LibraryFilter.Status -> "status:${it.name}"
                    else -> it::class.simpleName.orEmpty()
                }
            },
        ) { filter ->
            val selected = filter == state.filter
            FilterChip(
                selected = selected,
                onClick = { onFilter(if (selected) LibraryFilter.All else filter) },
                label = { Text(filter.label()) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderWidth = 0.dp,
                ),
            )
        }
    }
}

@Composable
private fun CountSortRow(
    state: LibraryUiState,
    onSort: (LibrarySort) -> Unit,
    onViewMode: (LibraryViewMode) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = Spacing.XXS, bottom = Spacing.XXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (state.books.size == state.totalBookCount) {
                pluralStringResource(R.plurals.book_count, state.totalBookCount, state.totalBookCount)
            } else {
                stringResource(R.string.books_shown_of_total, state.books.size, state.totalBookCount)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SortMenu(state.sort, onSort)
        when (state.viewMode) {
            LibraryViewMode.GRID -> IconButton(onClick = { onViewMode(LibraryViewMode.LIST) }) {
                Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = stringResource(R.string.show_as_list))
            }
            LibraryViewMode.LIST -> IconButton(onClick = { onViewMode(LibraryViewMode.GRID) }) {
                Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.show_as_grid))
            }
        }
    }
}

@Composable
private fun SortMenu(current: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(current.labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = stringResource(R.string.change_sort_order),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = MaterialTheme.shapes.medium) {
            LibrarySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.labelRes)) },
                    onClick = {
                        onSelect(sort)
                        expanded = false
                    },
                    trailingIcon = if (sort == current) {
                        { Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.state_selected)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyResults(
    state: LibraryUiState,
    onClear: () -> Unit,
    onImport: () -> Unit,
    onAddServer: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        if (state.totalBookCount == 0) {
            Text(stringResource(R.string.no_books_yet), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.no_books_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                TextButton(onClick = onImport) { Text(stringResource(R.string.import_book)) }
                if (state.activeProfile == null) TextButton(onClick = onAddServer) { Text(stringResource(R.string.connect_server)) }
            }
        } else {
            val what = if (state.query.isNotBlank()) stringResource(R.string.quoted, state.query.trim()) else state.filter.label()
            Text(stringResource(R.string.nothing_matches, what), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            TextButton(onClick = onClear, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = CircleShape) {
                Text(stringResource(R.string.show_all_books))
            }
        }
    }
}

/** Lets a full-span grid item ignore the grid's horizontal content padding (edge-to-edge rows). */
private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
        ),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun LibraryFilter.label(): String = when (this) {
    LibraryFilter.All -> stringResource(R.string.filter_all)
    LibraryFilter.Readaloud -> stringResource(R.string.format_readaloud)
    LibraryFilter.EbookOnly -> stringResource(R.string.format_ebook)
    LibraryFilter.AudioOnly -> stringResource(R.string.format_audiobook)
    LibraryFilter.Downloaded -> stringResource(R.string.filter_downloaded)
    LibraryFilter.Unread -> stringResource(R.string.filter_unread)
    is LibraryFilter.Status -> label
    is LibraryFilter.Tag -> name
}
