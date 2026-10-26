package org.hello.reader.core.data

import org.hello.reader.core.database.dao.AnnotationDao
import org.hello.reader.core.database.entity.AnnotationEntity
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.BookAnnotation
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.core.model.LocalLibrary
import org.hello.reader.core.security.SecurityVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationRepository(
    private val annotationDao: AnnotationDao,
    private val vault: SecurityVault,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /** Where a book's annotations live: the local library for imported books, else the active server. */
    private data class Scope(val serverId: String, val userId: String)

    private val localScope = Scope(LocalLibrary.SERVER_ID, LocalLibrary.USER_ID)

    private fun scopeFlow(bookId: String): Flow<Scope?> =
        if (LocalLibrary.isLocalBookId(bookId)) {
            flowOf(localScope)
        } else {
            vault.activeProfile.map { profile -> profile?.let { Scope(it.id, it.userId) } }
        }

    private fun currentScope(bookId: String): Scope? =
        if (LocalLibrary.isLocalBookId(bookId)) {
            localScope
        } else {
            vault.activeProfile.value?.let { Scope(it.id, it.userId) }
        }

    /** Annotation ids don't carry their book, so look in the active server's scope, then the local library. */
    private suspend fun findAnnotation(id: String): AnnotationEntity? {
        val active = vault.activeProfile.value
        return active?.let { annotationDao.getAnnotation(id, it.id, it.userId) }
            ?: annotationDao.getAnnotation(id, localScope.serverId, localScope.userId)
    }

    private fun observeScoped(bookId: String, type: AnnotationType?): Flow<List<BookAnnotation>> =
        scopeFlow(bookId).flatMapLatest { scope ->
            when {
                scope == null -> flowOf(emptyList())
                type == null -> annotationDao.observeAnnotations(scope.serverId, scope.userId, bookId)
                    .map { list -> list.map { it.toDomain() } }
                else -> annotationDao.observeAnnotationsByType(scope.serverId, scope.userId, bookId, type)
                    .map { list -> list.map { it.toDomain() } }
            }
        }

    fun observeAnnotations(bookId: String): Flow<List<BookAnnotation>> = observeScoped(bookId, null)

    /**
     * Highlights, notes and bookmarks across every book the user can open right now: the active
     * server's books plus imported ones (local only when no server is connected). Newest first.
     */
    fun observeAllAnnotations(): Flow<List<BookAnnotation>> =
        vault.activeProfile
            .map { profile -> profile?.let { Scope(it.id, it.userId) } ?: localScope }
            .distinctUntilChanged()
            .flatMapLatest { scope ->
                annotationDao.observeAnnotationsInScopes(
                    serverId = scope.serverId,
                    userId = scope.userId,
                    localServerId = localScope.serverId,
                    localUserId = localScope.userId,
                ).map { list -> list.map { it.toDomain() } }
            }

    fun observeHighlights(bookId: String): Flow<List<BookAnnotation>> = observeScoped(bookId, AnnotationType.HIGHLIGHT)

    fun observeBookmarks(bookId: String): Flow<List<BookAnnotation>> = observeScoped(bookId, AnnotationType.BOOKMARK)

    suspend fun addHighlight(
        bookId: String,
        selectedText: String,
        note: String? = null,
        color: HighlightColor = HighlightColor.YELLOW,
        textHref: String? = null,
        elementId: String? = null,
        startOffset: Int = 0,
        endOffset: Int = 0,
        chapterIndex: Int = 0,
        chapterTitle: String? = null,
    ): BookAnnotation? {
        val scope = currentScope(bookId) ?: return null
        val domain = BookAnnotation(
            id = idGenerator(),
            bookId = bookId,
            serverId = scope.serverId,
            userId = scope.userId,
            type = AnnotationType.HIGHLIGHT,
            selectedText = selectedText,
            note = note,
            color = color,
            textHref = textHref,
            elementId = elementId,
            startOffset = startOffset,
            endOffset = endOffset,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            createdAt = clock(),
        )
        annotationDao.upsertAnnotation(AnnotationEntity.fromDomain(domain))
        return domain
    }

    suspend fun addBookmark(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String? = null,
        textHref: String? = null,
        elementId: String? = null,
    ): BookAnnotation? {
        val scope = currentScope(bookId) ?: return null
        val domain = BookAnnotation(
            id = idGenerator(),
            bookId = bookId,
            serverId = scope.serverId,
            userId = scope.userId,
            type = AnnotationType.BOOKMARK,
            selectedText = null,
            note = null,
            color = HighlightColor.YELLOW,
            textHref = textHref,
            elementId = elementId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            createdAt = clock(),
        )
        annotationDao.upsertAnnotation(AnnotationEntity.fromDomain(domain))
        return domain
    }

    /**
     * Atomically modifies an existing annotation (e.g. note text or highlight color).
     * Strictly enforces that [id], [serverId], [userId], [bookId], and [createdAt] are immutable
     * to prevent cross-book or cross-tenant leakage.
     */
    suspend fun updateAnnotation(
        id: String,
        transform: (BookAnnotation) -> BookAnnotation,
    ): BookAnnotation? {
        val existing = findAnnotation(id)?.toDomain() ?: return null
        val modified = transform(existing).copy(
            id = existing.id,
            serverId = existing.serverId,
            userId = existing.userId,
            bookId = existing.bookId,
            createdAt = existing.createdAt,
        )
        annotationDao.upsertAnnotation(AnnotationEntity.fromDomain(modified))
        return modified
    }

    suspend fun updateNote(annotationId: String, note: String?): BookAnnotation? =
        updateAnnotation(annotationId) { it.copy(note = note) }

    suspend fun updateColor(annotationId: String, color: HighlightColor): BookAnnotation? =
        updateAnnotation(annotationId) { it.copy(color = color) }

    suspend fun deleteAnnotation(annotationId: String) {
        val existing = findAnnotation(annotationId) ?: return
        annotationDao.deleteAnnotation(existing.id, existing.serverId, existing.userId)
    }

    fun exportToMarkdown(
        bookTitle: String,
        bookAuthor: String?,
        annotations: List<BookAnnotation>,
    ): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        sb.append("# Notes & Highlights: $bookTitle\n")
        if (!bookAuthor.isNullOrBlank()) {
            sb.append("**Author:** $bookAuthor\n")
        }
        sb.append("**Exported:** ${dateFormat.format(Date(clock()))}\n\n")

        val sorted = annotations.sortedWith(
            compareBy<BookAnnotation> { it.chapterIndex }
                .thenBy { it.startOffset }
                .thenBy { it.createdAt },
        )

        var lastChapter = -1
        for (item in sorted) {
            if (item.chapterIndex != lastChapter) {
                lastChapter = item.chapterIndex
                val chTitle = item.chapterTitle ?: "Chapter ${lastChapter + 1}"
                sb.append("\n## $chTitle\n\n")
            }
            when (item.type) {
                AnnotationType.HIGHLIGHT -> {
                    sb.append("> \"${item.selectedText?.trim()}\"\n")
                    sb.append("*Color: ${item.color.displayName} · ${dateFormat.format(Date(item.createdAt))}*\n")
                    if (!item.note.isNullOrBlank()) {
                        sb.append("\n**Note:** ${item.note.trim()}\n")
                    }
                    sb.append("\n---\n\n")
                }
                AnnotationType.BOOKMARK -> {
                    sb.append("🔖 **Bookmark** (${dateFormat.format(Date(item.createdAt))})\n\n")
                }
            }
        }
        return sb.toString().trimEnd() + "\n"
    }
}
