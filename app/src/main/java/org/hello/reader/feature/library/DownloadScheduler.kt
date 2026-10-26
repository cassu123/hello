package org.hello.reader.feature.library

import android.content.Context
import org.hello.reader.core.download.BookDownloadWorker
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType

/** Seam over WorkManager so the library view model stays free of Android framework types. */
interface DownloadScheduler {
    fun enqueue(book: Book, requestedMediaType: BookMediaType? = null)
    fun cancel(book: Book)
}

class WorkManagerDownloadScheduler(context: Context) : DownloadScheduler {
    private val appContext = context.applicationContext

    override fun enqueue(book: Book, requestedMediaType: BookMediaType?) =
        BookDownloadWorker.enqueue(appContext, book.serverId, book.userId, book.id, requestedMediaType?.name)

    override fun cancel(book: Book) =
        BookDownloadWorker.cancel(appContext, book.serverId, book.id)
}
