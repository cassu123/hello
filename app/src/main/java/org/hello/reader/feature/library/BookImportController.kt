package org.hello.reader.feature.library

import org.hello.reader.core.data.UnsupportedBookFormatException
import org.hello.reader.core.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportEvent {
    data class Imported(val book: Book) : ImportEvent
    /** [unsupportedFileName] is set when the file isn't a book format Hello reads. */
    data class Failed(val unsupportedFileName: String? = null) : ImportEvent
}

/**
 * Runs local-book imports on an app-lifetime [scope], so copying a large
 * audiobook isn't cancelled when the user leaves the screen that started it.
 * Library and Settings both start imports here and observe the same progress.
 *
 * Generic over the source ([S] is `android.net.Uri` in the app) to stay JVM-testable.
 */
class BookImportController<S>(
    private val scope: CoroutineScope,
    private val importer: suspend (S) -> Result<Book>,
) {
    private val _inProgress = MutableStateFlow(0)

    /** Number of imports currently running. */
    val inProgress: StateFlow<Int> = _inProgress.asStateFlow()

    private val _events = MutableSharedFlow<ImportEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ImportEvent> = _events.asSharedFlow()

    fun import(source: S) {
        _inProgress.update { it + 1 }
        scope.launch {
            try {
                val result = importer(source)
                _events.emit(
                    result.fold(
                        onSuccess = { ImportEvent.Imported(it) },
                        onFailure = { ImportEvent.Failed((it as? UnsupportedBookFormatException)?.fileName) },
                    ),
                )
            } finally {
                _inProgress.update { it - 1 }
            }
        }
    }

}
