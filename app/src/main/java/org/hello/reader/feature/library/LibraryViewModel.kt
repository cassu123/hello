package org.hello.reader.feature.library

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.hello.reader.R
import org.hello.reader.app.appContainer
import org.hello.reader.core.data.BookRepository
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.database.entity.BookCollectionCrossRef
import org.hello.reader.core.database.entity.BookSeriesCrossRef
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookCollection
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.BookSeriesEntry
import org.hello.reader.core.model.CollectionGroupedBooks
import org.hello.reader.core.model.HomeShelfConfig
import org.hello.reader.core.model.HomeShelfType
import org.hello.reader.core.model.ReadingStatusItem
import org.hello.reader.core.model.Series
import org.hello.reader.core.model.SeriesWithBooks
import org.hello.reader.core.model.ServerProfile
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.core.network.NetworkModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

enum class LibraryTab(@StringRes val labelRes: Int) {
    BOOKS(R.string.tab_books),
    SERIES(R.string.tab_series),
    COLLECTIONS(R.string.tab_collections),
}

data class SeriesWithCovers(
    val series: Series,
    val coverUrls: List<String>,
)

data class LibraryUiState(
    val isLoading: Boolean = true,
    val activeProfile: ServerProfile? = null,
    val continueItems: List<ContinueItem> = emptyList(),
    val books: List<Book> = emptyList(),
    val totalBookCount: Int = 0,
    /** Titles for every book on the active server (unfiltered), for dialogs and messages. */
    /** Every book in the library (unfiltered) by id, for the book page. */
    val byId: Map<String, Book> = emptyMap(),
    /** Reading progress 0f..1f keyed by book id; books without a checkpoint are absent. */
    val progress: Map<String, Float> = emptyMap(),
    val tags: List<String> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.All,
    val sort: LibrarySort = LibrarySort.RECENT,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val activeTab: LibraryTab = LibraryTab.BOOKS,
    val series: List<SeriesWithCovers> = emptyList(),
    val collections: List<BookCollection> = emptyList(),
    val bookSeriesNames: Map<String, List<String>> = emptyMap(),
    val bookCollectionNames: Map<String, List<String>> = emptyMap(),
    val readingStatuses: List<LibraryFilter.Status> = emptyList(),
    val selectedBookIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = selectedBookIds.isNotEmpty(),
    val downloadedReadaloudIds: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val refreshError: String? = null,
    /** Newest additions first, for the Home shelf. */
    val recentlyAdded: List<Book> = emptyList(),
    /** Free-text search over title, author, narrator, tags and series. */
    val query: String = "",
    /** Whether the last catalog refresh reached the server; null before the first attempt or with no server. */
    val serverReachable: Boolean? = null,
)

class LibraryViewModel(
    private val repository: BookRepository,
    private val activeProfile: StateFlow<ServerProfile?>,
    private val downloads: DownloadScheduler,
    private val imports: BookImportController<Uri>? = null,
    private val preferencesRepository: ReaderPreferencesRepository? = null,
) : ViewModel() {

    /** Imports in progress (app-wide), for a progress indicator. */
    val importsInProgress: StateFlow<Int> = imports?.inProgress ?: MutableStateFlow(0)

    /** Import outcomes (app-wide), for a snackbar. */
    val importEvents: SharedFlow<ImportEvent> = imports?.events ?: MutableSharedFlow()

    private val filter = MutableStateFlow<LibraryFilter>(LibraryFilter.All)
    private val sort = MutableStateFlow(LibrarySort.RECENT)
    private val viewMode = MutableStateFlow(LibraryViewMode.GRID)
    private val activeTab = MutableStateFlow(LibraryTab.BOOKS)
    private val query = MutableStateFlow("")
    private val selectedBookIds = MutableStateFlow<Set<String>>(emptySet())
    private val refresh = MutableStateFlow(RefreshState())

    private data class RefreshState(
        val isLoaded: Boolean = false,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val reachable: Boolean? = null,
    )

    private data class Inputs(
        val filter: LibraryFilter,
        val sort: LibrarySort,
        val viewMode: LibraryViewMode,
        val refresh: RefreshState,
        val query: String,
        val activeTab: LibraryTab,
        val selectedBookIds: Set<String>,
    )

    private data class InputBase(
        val filter: LibraryFilter,
        val sort: LibrarySort,
        val viewMode: LibraryViewMode,
        val refresh: RefreshState,
    )

    private data class CoreData(
        val books: List<Book>,
        val profile: ServerProfile?,
        val continueBooks: List<Book>,
        val checkpoints: Map<String, SyncCheckpoint>,
        val downloadedReadaloudIds: Set<String>,
    )

    private data class SeriesData(
        val seriesList: List<Series>,
        val collsList: List<BookCollection>,
        val allBookSeriesRefs: List<BookSeriesCrossRef>,
        val allBookCollectionRefs: List<BookCollectionCrossRef>,
    )

    private val coreDataFlow: Flow<CoreData> = combine(
        repository.observeLibrary(),
        activeProfile,
        repository.observeContinueReading(),
        repository.observeCheckpoints(),
        repository.observeDownloadedReadaloudIds(),
    ) { books, profile, continueBooks, checkpoints, downloadedIds ->
        val localReadaloudIds = books.filter {
            it.mediaType == BookMediaType.READALOUD &&
                it.downloadStatus == org.hello.reader.core.model.DownloadStatus.DOWNLOADED &&
                !it.localFilePath.isNullOrBlank()
        }.map { it.id }.toSet()
        CoreData(books, profile, continueBooks, checkpoints, downloadedIds + localReadaloudIds)
    }

    private val seriesDataFlow: Flow<SeriesData> = combine(
        repository.observeSeries(),
        repository.observeCollections(),
        repository.observeAllBookSeries(),
        repository.observeAllBookCollections(),
    ) { seriesList, collsList, allBookSeriesRefs, allBookCollectionRefs ->
        SeriesData(seriesList, collsList, allBookSeriesRefs, allBookCollectionRefs)
    }

    private val inputsFlow: Flow<Inputs> = combine(
        combine(filter, sort, viewMode, refresh) { f, s, vm, r -> InputBase(f, s, vm, r) },
        query,
        activeTab,
        selectedBookIds,
    ) { base, q, tab, selected ->
        Inputs(base.filter, base.sort, base.viewMode, base.refresh, q, tab, selected)
    }

    val state: StateFlow<LibraryUiState> = combine(
        coreDataFlow,
        seriesDataFlow,
        inputsFlow,
    ) { core, series, inputs ->
        val books = core.books
        val profile = core.profile
        val continueBooks = core.continueBooks
        val checkpoints = core.checkpoints
        val seriesList = series.seriesList
        val collsList = series.collsList
        val allBookSeriesRefs = series.allBookSeriesRefs
        val allBookCollectionRefs = series.allBookCollectionRefs

        val booksById = books.associateBy { it.id }
        val seriesByUuid = seriesList.associateBy { it.uuid }
        val collsByUuid = collsList.associateBy { it.uuid }
        val refsBySeries = allBookSeriesRefs.groupBy { it.seriesUuid }
        val bookSeriesNames = allBookSeriesRefs.groupBy { it.bookId }
            .mapValues { (_, refs) -> refs.mapNotNull { seriesByUuid[it.seriesUuid]?.name } }
        val bookCollectionNames = allBookCollectionRefs.groupBy { it.bookId }
            .mapValues { (_, refs) -> refs.mapNotNull { collsByUuid[it.collectionUuid]?.name } }

        val seriesWithCovers = seriesList.map { s ->
            val refs = refsBySeries[s.uuid].orEmpty()
                .sortedWith { a, b ->
                    when {
                        a.position != null && b.position != null -> a.position.compareTo(b.position)
                        a.position != null && b.position == null -> -1
                        a.position == null && b.position != null -> 1
                        else -> 0
                    }
                }
            val covers = refs.mapNotNull { booksById[it.bookId]?.coverUrl }.take(3)
            SeriesWithCovers(series = s.copy(totalBooks = refs.size), coverUrls = covers)
        }.filter {
            inputs.query.isBlank() || it.series.name.contains(inputs.query, ignoreCase = true)
        }

        val filteredCollections = collsList.filter {
            inputs.query.isBlank() || it.name.contains(inputs.query, ignoreCase = true)
        }

        val readingStatuses = books
            .mapNotNull { b ->
                val name = b.readingStatus ?: "to_read"
                val label = b.readingStatusLabel ?: if (b.readingStatus == null) "To read" else name
                if (!name.isNullOrBlank() && !label.isNullOrBlank() && !name.equals("none", ignoreCase = true)) {
                    LibraryFilter.Status(name = name, label = label)
                } else null
            }
            .distinctBy { it.name.lowercase() }

        val tags = LibraryFilters.tagsOf(books)
        val effectiveFilter = when (val f = inputs.filter) {
            is LibraryFilter.Tag -> {
                if (tags.none { it.equals(f.name, ignoreCase = true) }) LibraryFilter.All else f
            }
            is LibraryFilter.Status -> {
                if (readingStatuses.none { it.name.equals(f.name, ignoreCase = true) }) LibraryFilter.All else f
            }
            else -> f
        }

        val filteredBooks = LibraryFilters.apply(books, checkpoints, effectiveFilter, inputs.sort)
            .filter {
                LibraryFilters.matchesQuery(
                    book = it,
                    query = inputs.query,
                    seriesNames = bookSeriesNames[it.id].orEmpty(),
                    collectionNames = bookCollectionNames[it.id].orEmpty(),
                )
            }

        val effectiveProgress = buildMap {
            for ((bookId, cp) in checkpoints) {
                val book = booksById[bookId]
                put(bookId, if (book != null) LibraryFilters.effectiveProgress(book, cp) else LibraryFilters.progressOf(cp))
            }
            for (book in books) {
                if (!containsKey(book.id)) {
                    put(book.id, LibraryFilters.effectiveProgress(book, checkpoints[book.id]))
                }
            }
        }

        LibraryUiState(
            isLoading = books.isEmpty() && !inputs.refresh.isLoaded,
            activeProfile = profile,
            continueItems = LibraryFilters.continueItems(continueBooks, checkpoints),
            books = filteredBooks,
            query = inputs.query,
            totalBookCount = books.size,
            byId = booksById,
            progress = effectiveProgress,
            tags = tags,
            filter = effectiveFilter,
            sort = inputs.sort,
            viewMode = inputs.viewMode,
            activeTab = inputs.activeTab,
            series = seriesWithCovers,
            collections = filteredCollections,
            bookSeriesNames = bookSeriesNames,
            bookCollectionNames = bookCollectionNames,
            readingStatuses = readingStatuses,
            selectedBookIds = inputs.selectedBookIds,
            downloadedReadaloudIds = core.downloadedReadaloudIds,
            isRefreshing = inputs.refresh.isRefreshing,
            refreshError = inputs.refresh.error,
            recentlyAdded = books.sortedByDescending { it.createdAt }.take(RECENTLY_ADDED_LIMIT),
            serverReachable = inputs.refresh.reachable.takeIf { profile != null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // Refresh on first load and every time the user switches to a different server.
        viewModelScope.launch {
            // Includes "no server": refresh() then just marks the (local) library as loaded,
            // so a fresh install shows its empty state instead of spinning forever.
            activeProfile.distinctUntilChangedBy { it?.id }.collect { refresh() }
        }
    }

    fun setActiveTab(tab: LibraryTab) {
        activeTab.value = tab
    }

    fun observeSeriesDetail(seriesUuid: String): Flow<SeriesWithBooks?> =
        repository.observeSeriesDetail(seriesUuid)

    fun observeCollectionDetail(collectionUuid: String): Flow<CollectionGroupedBooks?> =
        repository.observeCollectionDetail(collectionUuid)

    fun observeSeriesForBook(bookId: String): Flow<List<BookSeriesEntry>> =
        repository.observeSeriesForBook(bookId)

    fun observeCollectionsForBook(bookId: String): Flow<List<BookCollection>> =
        repository.observeCollectionsForBook(bookId)

    fun refresh() {
        if (refresh.value.isRefreshing) return
        if (activeProfile.value == null) {
            refresh.value = RefreshState(isLoaded = true, isRefreshing = false, error = null)
            return
        }
        refresh.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val result = repository.refreshCatalog()
            refresh.value = RefreshState(
                isLoaded = true,
                isRefreshing = false,
                reachable = result.isSuccess,
                // The technical reason; the screen words the message around it.
                error = result.exceptionOrNull()?.let { err -> err.localizedMessage ?: err.message ?: err.javaClass.simpleName },
            )
            // Bring in-progress books up to date; any real conflict is asked about when that book opens.
            repository.observeContinueReading().first().forEach { repository.syncBookPosition(it.id) }
        }
    }

    fun dismissError() = refresh.update { it.copy(error = null) }

    fun importBook(uri: Uri) {
        imports?.import(uri)
    }

    fun setFilter(value: LibraryFilter) {
        filter.value = value
    }

    fun setSort(value: LibrarySort) {
        sort.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setViewMode(value: LibraryViewMode) {
        viewMode.value = value
    }

    /**
     * Writes QUEUED to Room before enqueueing, so the card updates immediately and
     * the worker's own DOWNLOADING write can never be overwritten by a late QUEUED.
     */
    fun download(book: Book, format: BookMediaType? = null) {
        viewModelScope.launch {
            repository.markDownloadQueued(book)
            downloads.enqueue(book, format)
        }
    }

    /** The worker also resets status on cancellation; marking here covers a job that never started. */
    fun cancelDownload(book: Book) {
        downloads.cancel(book)
        viewModelScope.launch { repository.markDownloadCancelled(book) }
    }


    fun toggleBookSelection(bookId: String) {
        selectedBookIds.update { current ->
            if (bookId in current) current - bookId else current + bookId
        }
    }

    fun selectAllBooks(bookIds: List<String>) {
        selectedBookIds.update { current ->
            if (current.containsAll(bookIds) && bookIds.isNotEmpty()) {
                current - bookIds.toSet()
            } else {
                current + bookIds
            }
        }
    }

    fun clearSelection() {
        selectedBookIds.value = emptySet()
    }

    suspend fun calculateSelectedSize(): Long {
        val ids = selectedBookIds.value.toList()
        return repository.calculateTotalSizeForBooks(ids)
    }

    fun removeSelectedDownloads(onComplete: (count: Int, freedBytes: Long) -> Unit) {
        viewModelScope.launch {
            val ids = selectedBookIds.value.toList()
            val allBooks = state.value.byId.values.ifEmpty { state.value.books }
            val books = allBooks.filter { it.id in ids }
            val freedBytes = repository.removeDownloadsBulk(books)
            val count = books.size
            clearSelection()
            onComplete(count, freedBytes)
        }
    }

    fun bulkDownload(formats: Set<BookMediaType>) {
        viewModelScope.launch {
            val ids = selectedBookIds.value.toList()
            val allBooks = state.value.byId.values.ifEmpty { state.value.books }
            val books = allBooks.filter { it.id in ids }
            for (book in books) {
                val available = book.availableFormats().intersect(formats)
                for (format in available) {
                    download(book, format)
                }
            }
            clearSelection()
        }
    }

    fun observeDownloadsForBook(bookId: String): Flow<List<org.hello.reader.core.database.entity.BookDownloadEntity>> =
        repository.observeDownloadsForBook(bookId)

    fun removeDownload(book: Book, format: BookMediaType? = null) {
        viewModelScope.launch {
            repository.removeDownload(book, format)
        }
    }

    private val _availableReadingStatuses = MutableStateFlow<List<ReadingStatusItem>>(ReadingStatusItem.DEFAULT_STATUSES)
    val availableReadingStatuses: StateFlow<List<ReadingStatusItem>> = _availableReadingStatuses.asStateFlow()

    fun loadReadingStatuses() {
        viewModelScope.launch {
            val statuses = repository.getReadingStatuses()
            _availableReadingStatuses.value = statuses
        }
    }

    fun updateReadingStatus(book: Book, statusItem: ReadingStatusItem?, onError: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val result = repository.updateBookReadingStatus(book.id, statusItem)
            if (result.isFailure) {
                onError?.invoke(result.exceptionOrNull()?.localizedMessage ?: "Failed to update reading status")
            }
        }
    }

    fun setRating(book: Book, rating: Float?) {
        viewModelScope.launch {
            repository.setBookRating(book.id, rating)
        }
    }

    fun setPreferredMediaType(book: Book, mediaType: BookMediaType) {
        val isReadaloud = book.hasReadaloud || book.mediaType == BookMediaType.READALOUD
        if (isReadaloud && mediaType != BookMediaType.READALOUD) {
            return
        }
        viewModelScope.launch {
            repository.updateBookMediaType(book, mediaType)
        }
    }

    private val _serverHomeSections = MutableStateFlow<List<HomeShelfConfig>>(emptyList())
    val serverHomeSections: StateFlow<List<HomeShelfConfig>> = _serverHomeSections.asStateFlow()

    private val _homeShelves = MutableStateFlow<List<HomeShelfConfig>>(HomeShelfConfig.DEFAULT_SHELVES)
    val homeShelves: StateFlow<List<HomeShelfConfig>> = _homeShelves.asStateFlow()

    fun loadHomeShelves() {
        viewModelScope.launch {
            val profileKey = repository.activeProfileKey()
            val storedJson = try {
                preferencesRepository?.homeShelvesJson(profileKey)?.firstOrNull()
            } catch (_: Exception) {
                null
            }
            val userShelves: List<HomeShelfConfig>? = if (!storedJson.isNullOrBlank()) {
                try {
                    NetworkModule.json.decodeFromString<List<HomeShelfConfig>>(storedJson)
                } catch (_: Exception) {
                    null
                }
            } else null

            val serverSections = repository.fetchServerHomeSections()
            _serverHomeSections.value = serverSections

            val baseShelves = userShelves ?: HomeShelfConfig.DEFAULT_SHELVES

            val existingIds = baseShelves.map { it.id }.toSet()
            val missingDefaults = HomeShelfConfig.DEFAULT_SHELVES.filter { it.id !in existingIds }

            val serverSectionMap = serverSections.associateBy { it.id }
            val updatedBaseShelves = baseShelves.map { shelf ->
                if (shelf.type == HomeShelfType.SERVER_SECTION) {
                    serverSectionMap[shelf.id]?.let { serverShelf ->
                        shelf.copy(
                            title = serverShelf.title,
                            bookIds = serverShelf.bookIds,
                        )
                    } ?: shelf
                } else shelf
            }

            val newServerShelves = serverSections.filter { it.id !in existingIds }

            _homeShelves.value = updatedBaseShelves + missingDefaults + newServerShelves
        }
    }

    fun updateHomeShelves(shelves: List<HomeShelfConfig>) {
        _homeShelves.value = shelves
        viewModelScope.launch {
            persistHomeShelves(shelves)
        }
    }

    suspend fun persistHomeShelves(shelves: List<HomeShelfConfig>) {
        preferencesRepository?.let { prefs ->
            try {
                val profileKey = repository.activeProfileKey()
                val json = NetworkModule.json.encodeToString(shelves)
                prefs.saveHomeShelvesJson(json, profileKey)
            } catch (_: Exception) {
            }
        }
    }

    fun resetHomeShelvesToDefault() {
        val defaultList = HomeShelfConfig.DEFAULT_SHELVES + _serverHomeSections.value
        updateHomeShelves(defaultList)
    }

    companion object {
        const val RECENTLY_ADDED_LIMIT = 12

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer
                LibraryViewModel(
                    repository = container.bookRepository,
                    activeProfile = container.vault.activeProfile,
                    downloads = WorkManagerDownloadScheduler(checkNotNull(this[APPLICATION_KEY])),
                    imports = container.bookImports,
                    preferencesRepository = container.readerPreferencesRepository,
                )
            }
        }
    }
}
