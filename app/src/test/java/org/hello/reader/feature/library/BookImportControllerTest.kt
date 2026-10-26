package org.hello.reader.feature.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.hello.reader.core.data.LocalBookImporter
import org.hello.reader.core.data.UnsupportedBookFormatException
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookImportControllerTest {

    private val book = Book(id = "local_1", serverId = "local", title = "Dune", authors = listOf("Frank Herbert"), mediaType = BookMediaType.EBOOK_ONLY)

    @Test
    fun `reports progress and success, and keeps running when the starter goes away`() = runTest {
        val gate = CompletableDeferred<Result<Book>>()
        val appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = BookImportController<String>(appScope) { gate.await() }
        val events = mutableListOf<ImportEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.toList(events) }

        controller.import("content://dune.epub")
        assertEquals(1, controller.inProgress.value)

        gate.complete(Result.success(book))
        controller.inProgress.first { it == 0 }
        assertEquals(listOf<ImportEvent>(ImportEvent.Imported(book)), events)
        collector.cancel()
    }

    @Test
    fun `unsupported files get a specific message, other failures a generic one`() = runTest {
        val appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = BookImportController<String>(appScope) { name ->
            if (name.endsWith(".pdf")) Result.failure(UnsupportedBookFormatException(name)) else Result.failure(java.io.IOException("disk full"))
        }
        val events = mutableListOf<ImportEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.toList(events) }

        controller.import("notes.pdf")
        controller.import("broken.epub")

        val failures = events.map { it as ImportEvent.Failed }
        assertEquals("notes.pdf", failures[0].unsupportedFileName)
        assertEquals(null, failures[1].unsupportedFileName)
        assertEquals(0, controller.inProgress.value)
        collector.cancel()
    }

    @Test
    fun `mime types map to supported extensions for providers without file names`() {
        assertEquals("epub", LocalBookImporter.extensionForMimeType("application/epub+zip"))
        assertEquals("m4b", LocalBookImporter.extensionForMimeType("audio/mp4"))
        assertEquals("mp3", LocalBookImporter.extensionForMimeType("AUDIO/MPEG"))
        assertNull(LocalBookImporter.extensionForMimeType("application/pdf"))
        assertNull(LocalBookImporter.extensionForMimeType(null))
    }

}
