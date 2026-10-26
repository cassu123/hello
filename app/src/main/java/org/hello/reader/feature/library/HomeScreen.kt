package org.hello.reader.feature.library

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import org.hello.reader.core.model.BookMediaType
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import org.hello.reader.core.model.BookCollection
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.HomeShelfConfig
import org.hello.reader.core.model.HomeShelfType
import org.hello.reader.feature.onboarding.switcher.ServerSwitcherSheet
import org.hello.reader.feature.onboarding.switcher.ServerSwitcherViewModel
import org.hello.reader.ui.components.HelloGreeting
import org.hello.reader.ui.components.ScreenTitle
import org.hello.reader.ui.components.SectionHeader
import org.hello.reader.ui.components.StatusDot
import org.hello.reader.ui.theme.LocalHelloColors
import org.hello.reader.ui.theme.Spacing
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.hello.reader.R

/** MIME types offered by the system picker; the importer still validates the actual file. */
internal val ImportMimeTypes = arrayOf("application/epub+zip", "audio/*", "*/*")

@Composable
internal fun rememberImportLauncher(viewModel: LibraryViewModel): ManagedActivityResultLauncher<Array<String>, Uri?> =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBook(uri)
    }

/** Snackbar text for a failed import. */
internal fun ImportEvent.Failed.message(context: Context): String =
    unsupportedFileName?.let { context.getString(R.string.import_unsupported, it) } ?: context.getString(R.string.import_failed)

/** Shows import results and refresh failures as snackbars while this screen is visible. */
@Composable
internal fun LibrarySnackbarEffects(viewModel: LibraryViewModel, refreshError: String?, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.importEvents.collect { event ->
            when (event) {
                is ImportEvent.Imported -> snackbar.showSnackbar(context.getString(R.string.import_added, event.book.title))
                is ImportEvent.Failed -> snackbar.showSnackbar(event.message(context))
            }
        }
    }
    LaunchedEffect(refreshError) {
        val message = refreshError ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            context.getString(R.string.refresh_failed, message),
            actionLabel = context.getString(R.string.retry),
        )
        viewModel.dismissError()
        if (result == SnackbarResult.ActionPerformed) viewModel.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenBook: (Book) -> Unit,
    onOpenDetails: (Book) -> Unit,
    onSeeAll: () -> Unit,
    onAddServer: () -> Unit,
    viewModel: LibraryViewModel,
    onOpenAudio: (Book) -> Unit = onOpenBook,
    switcherViewModel: ServerSwitcherViewModel = viewModel(factory = ServerSwitcherViewModel.Factory),
    onOpenSeries: (String) -> Unit = {},
    onOpenCollection: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importing by viewModel.importsInProgress.collectAsStateWithLifecycle()
    val homeShelves by viewModel.homeShelves.collectAsStateWithLifecycle()
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    var showCustomizeShelves by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val importLauncher = rememberImportLauncher(viewModel)
    LibrarySnackbarEffects(viewModel, state.refreshError, snackbar)

    LaunchedEffect(Unit) {
        viewModel.loadHomeShelves()
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.L)) {
                item(key = "title") {
                    ScreenTitle(title = { HelloGreeting(stringResource(R.string.home_greeting)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showCustomizeShelves = true }) {
                                Icon(
                                    Icons.Outlined.Tune,
                                    contentDescription = stringResource(R.string.customize_shelves),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ServerChip(state = state, onClick = { if (state.activeProfile != null) showSwitcher = true else onAddServer() })
                        }
                    }
                }
                when {
                    state.isLoading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.totalBookCount == 0 && state.continueItems.isEmpty() -> item(key = "empty") {
                        WelcomeEmptyState(
                            connected = state.activeProfile != null,
                            importing = importing > 0,
                            onImport = { importLauncher.launch(ImportMimeTypes) },
                            onAddServer = onAddServer,
                            onRefresh = viewModel::refresh,
                        )
                    }
                    else -> {
                        var hasRenderedShelf = false
                        val enabledShelves = homeShelves.filter { it.isEnabled }

                        enabledShelves.forEach { shelf ->
                            when (shelf.type) {
                                HomeShelfType.CONTINUE_READING -> {
                                    val hero = state.continueItems.firstOrNull()
                                    if (hero != null) {
                                        hasRenderedShelf = true
                                        item(key = "continue-header") {
                                            SectionHeader(stringResource(R.string.continue_reading), Modifier.padding(top = Spacing.S))
                                        }
                                        item(key = "continue-hero") {
                                            ContinueHeroCard(
                                                item = hero,
                                                onResume = { onOpenBook(hero.book) },
                                                onOpenDetails = { onOpenDetails(hero.book) },
                                                modifier = Modifier.padding(horizontal = Spacing.Gutter),
                                            )
                                        }
                                    }
                                    val others = state.continueItems.drop(1)
                                    if (others.isNotEmpty()) {
                                        hasRenderedShelf = true
                                        item(key = "progress-header") {
                                            SectionHeader(stringResource(R.string.also_in_progress), Modifier.padding(top = Spacing.L))
                                        }
                                        item(key = "progress-row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = Spacing.Gutter),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                                            ) {
                                                items(others, key = { it.book.id }) { item ->
                                                    InProgressItem(item, onOpen = { onOpenBook(item.book) })
                                                }
                                            }
                                        }
                                    }
                                }
                                HomeShelfType.CURRENTLY_READING -> {
                                    val readingBooks = state.books.filter { b ->
                                        b.readingStatus?.lowercase() in listOf("reading", "currently_reading", "in_progress") ||
                                            (b.readingStatus == null && (state.progress[b.id] ?: 0f) in 0.01f..0.99f && !b.isRead)
                                    }
                                    if (readingBooks.isNotEmpty()) {
                                        hasRenderedShelf = true
                                        item(key = "shelf_${shelf.id}_header") {
                                            SectionHeader(shelf.displayTitle(), Modifier.padding(top = Spacing.L))
                                        }
                                        item(key = "shelf_${shelf.id}_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = Spacing.Gutter),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                                            ) {
                                                items(readingBooks, key = { it.id }) { book ->
                                                    val isReadaloud = book.id in state.downloadedReadaloudIds ||
                                                        (book.mediaType == BookMediaType.READALOUD && book.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED && !book.localFilePath.isNullOrBlank())
                                                    BookGridItem(
                                                        book = book,
                                                        progress = state.progress[book.id] ?: 0f,
                                                        onOpen = { onOpenDetails(book) },
                                                        modifier = Modifier.width(108.dp),
                                                        hasDownloadedReadaloud = isReadaloud,
                                                        onOpenRead = { onOpenBook(book) },
                                                        onOpenListen = { onOpenAudio(book) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                HomeShelfType.TO_READ -> {
                                    val toReadBooks = state.books.filter { b ->
                                        b.readingStatus?.lowercase() in listOf("to_read", "to-read", "toread", "want_to_read") ||
                                            (b.readingStatus == null && !b.isRead && (state.progress[b.id] ?: 0f) < 0.01f)
                                    }
                                    if (toReadBooks.isNotEmpty()) {
                                        hasRenderedShelf = true
                                        item(key = "shelf_${shelf.id}_header") {
                                            SectionHeader(shelf.displayTitle(), Modifier.padding(top = Spacing.L))
                                        }
                                        item(key = "shelf_${shelf.id}_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = Spacing.Gutter),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                                            ) {
                                                items(toReadBooks, key = { it.id }) { book ->
                                                    val isReadaloud = book.id in state.downloadedReadaloudIds ||
                                                        (book.mediaType == BookMediaType.READALOUD && book.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED && !book.localFilePath.isNullOrBlank())
                                                    BookGridItem(
                                                        book = book,
                                                        progress = state.progress[book.id] ?: 0f,
                                                        onOpen = { onOpenDetails(book) },
                                                        modifier = Modifier.width(108.dp),
                                                        hasDownloadedReadaloud = isReadaloud,
                                                        onOpenRead = { onOpenBook(book) },
                                                        onOpenListen = { onOpenAudio(book) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                HomeShelfType.RECENTLY_ADDED -> {
                                    if (state.recentlyAdded.isNotEmpty()) {
                                        hasRenderedShelf = true
                                        item(key = "shelf_${shelf.id}_header") {
                                            SectionHeader(
                                                shelf.displayTitle(),
                                                Modifier.padding(top = Spacing.L),
                                                actionLabel = stringResource(R.string.see_all),
                                                onAction = onSeeAll,
                                            )
                                        }
                                        item(key = "shelf_${shelf.id}_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = Spacing.Gutter),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                                            ) {
                                                items(state.recentlyAdded, key = { it.id }) { book ->
                                                    val isReadaloud = book.id in state.downloadedReadaloudIds ||
                                                        (book.mediaType == BookMediaType.READALOUD && book.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED && !book.localFilePath.isNullOrBlank())
                                                    BookGridItem(
                                                        book = book,
                                                        progress = state.progress[book.id] ?: 0f,
                                                        onOpen = { onOpenDetails(book) },
                                                        modifier = Modifier.width(108.dp),
                                                        hasDownloadedReadaloud = isReadaloud,
                                                        onOpenRead = { onOpenBook(book) },
                                                        onOpenListen = { onOpenAudio(book) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                HomeShelfType.COMPLETED -> {
                                    val completedBooks = state.books.filter { b ->
                                        b.isRead || (state.progress[b.id] ?: 0f) >= 1.0f
                                    }
                                    if (completedBooks.isNotEmpty()) {
                                        hasRenderedShelf = true
                                        item(key = "shelf_${shelf.id}_header") {
                                            SectionHeader(shelf.displayTitle(), Modifier.padding(top = Spacing.L))
                                        }
                                        item(key = "shelf_${shelf.id}_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = Spacing.Gutter),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                                            ) {
                                                items(completedBooks, key = { it.id }) { book ->
                                                    val isReadaloud = book.id in state.downloadedReadaloudIds ||
                                                        (book.mediaType == BookMediaType.READALOUD && book.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED && !book.localFilePath.isNullOrBlank())
                                                    BookGridItem(
                                                        book = book,
                                                        progress = state.progress[book.id] ?: 0f,
                                                        onOpen = { onOpenDetails(book) },
                                                        modifier = Modifier.width(108.dp),
                                                        hasDownloadedReadaloud = isReadaloud,
                                                        onOpenRead = { onOpenBook(book) },
                                                        onOpenListen = { onOpenAudio(book) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                HomeShelfType.SERIES -> {
                                    val availableMap = state.series.associateBy { it.series.uuid }
                                    val displayedSeries = if (shelf.selectedIds.isEmpty()) {
                                        state.series
                                    } else {
                                        val valid = shelf.selectedIds.mapNotNull { availableMap[it] }
                                        if (valid.isEmpty()) state.series else valid
                                    }
                                    if (displayedSeries.isNotEmpty()) {
                                        hasRenderedShelf = true
                                        item(key = "shelf_${shelf.id}_header") {
                                            SectionHeader(shelf.displayTitle(), Modifier.padding(top = Spacing.L))
                                        }
                                        item(key = "shelf_${shelf.id}_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = Spacing.Gutter),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                                            ) {
                                                items(displayedSeries, key = { it.series.uuid }) { item ->
                                                    SeriesCard(
                                                        item = item,
                                                        onClick = { onOpenSeries(item.series.uuid) },
                                                        modifier = Modifier.width(160.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                HomeShelfType.COLLECTIONS -> {
                                    val availableMap = state.collections.associateBy { it.uuid }
                                    val displayedCollections = if (shelf.selectedIds.isEmpty()) {
                                        state.collections
                                    } else {
                                        val valid = shelf.selectedIds.mapNotNull { availableMap[it] }
                                        if (valid.isEmpty()) state.collections else valid
                                    }
                                    if (displayedCollections.isNotEmpty()) {
                                        hasRenderedShelf = true
                                        item(key = "shelf_${shelf.id}_header") {
                                            SectionHeader(shelf.displayTitle(), Modifier.padding(top = Spacing.L))
                                        }
                                        item(key = "shelf_${shelf.id}_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = Spacing.Gutter),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                                            ) {
                                                items(displayedCollections, key = { it.uuid }) { col ->
                                                    CollectionCard(
                                                        collection = col,
                                                        onClick = { onOpenCollection(col.uuid) },
                                                        modifier = Modifier.width(200.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                HomeShelfType.SERVER_SECTION -> {
                                    val sectionBooks = shelf.bookIds.mapNotNull { state.byId[it] }
                                    if (sectionBooks.isNotEmpty()) {
                                        hasRenderedShelf = true
                                        item(key = "shelf_${shelf.id}_header") {
                                            SectionHeader(shelf.displayTitle(), Modifier.padding(top = Spacing.L))
                                        }
                                        item(key = "shelf_${shelf.id}_row") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = Spacing.Gutter),
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                                            ) {
                                                items(sectionBooks, key = { it.id }) { book ->
                                                    val isReadaloud = book.id in state.downloadedReadaloudIds ||
                                                        (book.mediaType == BookMediaType.READALOUD && book.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED && !book.localFilePath.isNullOrBlank())
                                                    BookGridItem(
                                                        book = book,
                                                        progress = state.progress[book.id] ?: 0f,
                                                        onOpen = { onOpenDetails(book) },
                                                        modifier = Modifier.width(108.dp),
                                                        hasDownloadedReadaloud = isReadaloud,
                                                        onOpenRead = { onOpenBook(book) },
                                                        onOpenListen = { onOpenAudio(book) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!hasRenderedShelf) {
                            item(key = "no_shelves_visible") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp, horizontal = Spacing.Gutter),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            stringResource(R.string.no_shelves_visible),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                        Spacer(Modifier.height(Spacing.S))
                                        OutlinedButton(onClick = { showCustomizeShelves = true }) {
                                            Text(stringResource(R.string.customize_shelves))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showSwitcher) {
        ServerSwitcherSheet(
            onDismiss = { showSwitcher = false },
            onAddServer = {
                showSwitcher = false
                onAddServer()
            },
            viewModel = switcherViewModel,
        )
    }

    if (showCustomizeShelves) {
        CustomizeShelvesSheet(
            shelves = homeShelves,
            seriesList = state.series,
            collectionsList = state.collections,
            onSave = viewModel::updateHomeShelves,
            onResetDefault = viewModel::resetHomeShelvesToDefault,
            onDismiss = { showCustomizeShelves = false },
        )
    }
}

@Composable
fun HomeShelfConfig.displayTitle(): String = when (type) {
    HomeShelfType.CONTINUE_READING -> stringResource(R.string.shelf_continue_reading)
    HomeShelfType.CURRENTLY_READING -> stringResource(R.string.shelf_reading)
    HomeShelfType.TO_READ -> stringResource(R.string.shelf_to_read)
    HomeShelfType.RECENTLY_ADDED -> stringResource(R.string.shelf_recently_added)
    HomeShelfType.COMPLETED -> stringResource(R.string.shelf_read)
    HomeShelfType.SERIES -> if (title.isNotBlank() && title != "Series") title else stringResource(R.string.shelf_series)
    HomeShelfType.COLLECTIONS -> if (title.isNotBlank() && title != "Collections") title else stringResource(R.string.shelf_collections)
    HomeShelfType.SERVER_SECTION -> title
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeShelvesSheet(
    shelves: List<HomeShelfConfig>,
    seriesList: List<SeriesWithCovers>,
    collectionsList: List<BookCollection>,
    onSave: (List<HomeShelfConfig>) -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    var workingShelves by remember(shelves) { mutableStateOf(shelves) }
    var editingShelfIndex by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.M),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.XS),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.customize_shelves),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = {
                    onResetDefault()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.reset_default))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = Spacing.XS), color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
            ) {
                itemsIndexed(workingShelves, key = { _, s -> s.id }) { index, shelf ->
                    val isConfigurable = shelf.type == HomeShelfType.SERIES || shelf.type == HomeShelfType.COLLECTIONS
                    val isCustomAdded = shelf.id !in HomeShelfConfig.DEFAULT_SHELVES.map { it.id } && shelf.type != HomeShelfType.SERVER_SECTION

                    ListItem(
                        modifier = if (isConfigurable) Modifier.clickable { editingShelfIndex = index } else Modifier,
                        headlineContent = {
                            Text(
                                shelf.displayTitle(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        supportingContent = if (isConfigurable) {
                            {
                                Text(
                                    if (shelf.selectedIds.isEmpty()) stringResource(R.string.showing_all)
                                    else pluralStringResource(R.plurals.showing_selected, shelf.selectedIds.size, shelf.selectedIds.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else null,
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isConfigurable) {
                                    IconButton(onClick = { editingShelfIndex = index }) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = stringResource(R.string.filter_series),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (isCustomAdded) {
                                    IconButton(onClick = {
                                        workingShelves = workingShelves.filterIndexed { i, _ -> i != index }
                                    }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.delete_shelf),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val list = workingShelves.toMutableList()
                                            val item = list.removeAt(index)
                                            list.add(index - 1, item)
                                            workingShelves = list
                                        }
                                    },
                                    enabled = index > 0,
                                ) {
                                    Icon(
                                        Icons.Outlined.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.move_shelf_up),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < workingShelves.lastIndex) {
                                            val list = workingShelves.toMutableList()
                                            val item = list.removeAt(index)
                                            list.add(index + 1, item)
                                            workingShelves = list
                                        }
                                    },
                                    enabled = index < workingShelves.lastIndex,
                                ) {
                                    Icon(
                                        Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.move_shelf_down),
                                    )
                                }
                                Switch(
                                    checked = shelf.isEnabled,
                                    onCheckedChange = { checked ->
                                        val list = workingShelves.toMutableList()
                                        list[index] = shelf.copy(isEnabled = checked)
                                        workingShelves = list
                                    },
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.XXS),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                OutlinedButton(
                    onClick = {
                        val newShelf = HomeShelfConfig(
                            id = "series_${java.util.UUID.randomUUID()}",
                            title = "Series",
                            type = HomeShelfType.SERIES,
                            isEnabled = true,
                        )
                        workingShelves = workingShelves + newShelf
                        editingShelfIndex = workingShelves.lastIndex
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.XXS))
                    Text(stringResource(R.string.add_series_shelf))
                }
                OutlinedButton(
                    onClick = {
                        val newShelf = HomeShelfConfig(
                            id = "collections_${java.util.UUID.randomUUID()}",
                            title = "Collections",
                            type = HomeShelfType.COLLECTIONS,
                            isEnabled = true,
                        )
                        workingShelves = workingShelves + newShelf
                        editingShelfIndex = workingShelves.lastIndex
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.XXS))
                    Text(stringResource(R.string.add_collections_shelf))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = Spacing.XS), color = MaterialTheme.colorScheme.outlineVariant)

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.XS),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Button(
                    onClick = {
                        onSave(workingShelves)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }

    editingShelfIndex?.let { idx ->
        workingShelves.getOrNull(idx)?.let { shelfToEdit ->
            ShelfSelectionSheet(
                shelf = shelfToEdit,
                allSeries = seriesList,
                allCollections = collectionsList,
                onSave = { newTitle, newSelectedIds ->
                    val list = workingShelves.toMutableList()
                    list[idx] = shelfToEdit.copy(title = newTitle, selectedIds = newSelectedIds)
                    workingShelves = list
                    editingShelfIndex = null
                },
                onDismiss = { editingShelfIndex = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfSelectionSheet(
    shelf: HomeShelfConfig,
    allSeries: List<SeriesWithCovers>,
    allCollections: List<BookCollection>,
    onSave: (title: String, selectedIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(shelf) { mutableStateOf(shelf.title) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedIds by remember(shelf) { mutableStateOf(shelf.selectedIds) }

    data class ItemInfo(
        val id: String,
        val name: String,
        val countText: String,
        val coverUrls: List<String> = emptyList(),
        val colorHex: String? = null,
    )

    val items = remember(shelf.type, allSeries, allCollections) {
        when (shelf.type) {
            HomeShelfType.SERIES -> allSeries.map { s ->
                ItemInfo(
                    id = s.series.uuid,
                    name = s.series.name,
                    countText = "${s.series.totalBooks} books",
                    coverUrls = s.coverUrls,
                )
            }
            HomeShelfType.COLLECTIONS -> allCollections.map { c ->
                ItemInfo(
                    id = c.uuid,
                    name = c.name,
                    countText = "${c.totalBooks} books",
                    colorHex = c.color,
                )
            }
            else -> emptyList()
        }
    }

    val filteredItems = remember(items, searchQuery) {
        if (searchQuery.isBlank()) items
        else items.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.M),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.XS),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (shelf.type == HomeShelfType.SERIES) stringResource(R.string.select_series) else stringResource(R.string.select_collections),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = {
                    onSave(title.ifBlank { shelf.title }, selectedIds)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.done))
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.shelf_title)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.XXS),
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.XXS),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.XXS),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selectedIds.isEmpty()) stringResource(R.string.showing_all)
                    else pluralStringResource(R.plurals.showing_selected, selectedIds.size, selectedIds.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = {
                        selectedIds = items.map { it.id }
                    }) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(onClick = {
                        selectedIds = emptyList()
                    }) {
                        Text(stringResource(R.string.clear_all))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    val isChecked = item.id in selectedIds
                    ListItem(
                        modifier = Modifier.clickable {
                            selectedIds = if (isChecked) {
                                selectedIds - item.id
                            } else {
                                selectedIds + item.id
                            }
                        },
                        leadingContent = {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                                },
                            )
                        },
                        headlineContent = {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                item.countText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            if (item.coverUrls.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.size(width = 36.dp, height = 48.dp),
                                ) {
                                    CoverPreview(
                                        url = item.coverUrls.first(),
                                        title = item.name,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else if (item.colorHex != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = parseCardColor(item.colorHex, MaterialTheme.colorScheme.primaryContainer),
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.CollectionsBookmark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

/** "● Online" / "● Offline" / "This device" pill that opens the server switcher. */
@Composable
private fun ServerChip(state: LibraryUiState, onClick: () -> Unit) {
    val (label, dot) = when {
        state.activeProfile == null -> stringResource(R.string.server_this_device) to null
        state.serverReachable == false -> stringResource(R.string.server_offline) to MaterialTheme.colorScheme.outline
        state.isRefreshing && state.serverReachable == null -> stringResource(R.string.server_connecting) to MaterialTheme.colorScheme.outline
        else -> stringResource(R.string.server_online) to LocalHelloColors.current.success
    }
    val description = stringResource(R.string.server_chip_description, label)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .padding(end = Spacing.S)
            .semantics { contentDescription = description },
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.S, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dot != null) StatusDot(dot)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WelcomeEmptyState(
    connected: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onAddServer: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            if (connected) {
                stringResource(R.string.library_empty_connected)
            } else {
                stringResource(R.string.library_empty_local)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.XS),
        )
        Spacer(Modifier.height(Spacing.L))
        Button(onClick = onImport, enabled = !importing, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (importing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(Spacing.XS))
                Text(stringResource(R.string.importing))
            } else {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.XS))
                Text(stringResource(R.string.import_book))
            }
        }
        Spacer(Modifier.height(Spacing.XS))
        OutlinedButton(
            onClick = if (connected) onRefresh else onAddServer,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (!connected) {
                Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.XS))
            }
            Text(stringResource(if (connected) R.string.refresh else R.string.connect_server))
        }
    }
}

/** Icon button that becomes a spinner while imports run. */
@Composable
internal fun ImportAction(importing: Boolean, onImport: () -> Unit) {
    if (importing) {
        val description = stringResource(R.string.importing_book)
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                Modifier
                    .size(20.dp)
                    .semantics { contentDescription = description },
                strokeWidth = 2.dp,
            )
        }
    } else {
        IconButton(onClick = onImport) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.import_book))
        }
    }
}
