package org.hello.reader.feature.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.hello.reader.app.appContainer
import org.hello.reader.core.data.AnnotationRepository
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookAnnotation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import org.hello.reader.R

enum class NotebookFilter(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    HIGHLIGHTS(R.string.filter_highlights),
    NOTES(R.string.filter_notes),
    BOOKMARKS(R.string.filter_bookmarks),
    ;

    fun matches(annotation: BookAnnotation): Boolean = when (this) {
        ALL -> true
        HIGHLIGHTS -> annotation.type == AnnotationType.HIGHLIGHT
        NOTES -> !annotation.note.isNullOrBlank()
        BOOKMARKS -> annotation.type == AnnotationType.BOOKMARK
    }
}

/** One book's annotations, in reading order. */
data class NotebookSection(
    val bookId: String,
    val book: Book?,
    val entries: List<BookAnnotation>,
)

data class NotebookUiState(
    val isLoading: Boolean = true,
    val filter: NotebookFilter = NotebookFilter.ALL,
    val sections: List<NotebookSection> = emptyList(),
    val totalCount: Int = 0,
)

class NotebookViewModel(
    annotations: Flow<List<BookAnnotation>>,
    books: Flow<List<Book>>,
    private val repository: AnnotationRepository? = null,
) : ViewModel() {

    private val filter = MutableStateFlow(NotebookFilter.ALL)

    val state: StateFlow<NotebookUiState> = combine(annotations, books, filter) { all, library, selected ->
        NotebookUiState(
            isLoading = false,
            filter = selected,
            sections = group(all.filter(selected::matches), library),
            totalCount = all.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebookUiState())

    fun setFilter(value: NotebookFilter) {
        filter.value = value
    }

    fun delete(annotation: BookAnnotation) {
        val repo = repository ?: return
        viewModelScope.launch { repo.deleteAnnotation(annotation.id) }
    }

    companion object {
        /**
         * Books ordered by their most recent annotation; entries inside a book in reading order.
         * Annotations for books no longer in the library keep a section with a null [Book].
         */
        fun group(annotations: List<BookAnnotation>, library: List<Book>): List<NotebookSection> {
            val byId = library.associateBy { it.id }
            return annotations
                .groupBy { it.bookId }
                .map { (bookId, entries) ->
                    NotebookSection(
                        bookId = bookId,
                        book = byId[bookId],
                        entries = entries.sortedWith(
                            compareBy<BookAnnotation> { it.chapterIndex }
                                .thenBy { it.startOffset }
                                .thenBy { it.createdAt },
                        ),
                    )
                }
                .sortedByDescending { section -> section.entries.maxOf { it.createdAt } }
        }

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer
                NotebookViewModel(
                    annotations = container.annotationRepository.observeAllAnnotations(),
                    books = container.bookRepository.observeLibrary(),
                    repository = container.annotationRepository,
                )
            }
        }
    }
}
