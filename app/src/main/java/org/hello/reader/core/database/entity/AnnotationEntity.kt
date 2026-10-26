package org.hello.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.BookAnnotation
import org.hello.reader.core.model.HighlightColor

@Entity(
    tableName = "annotations",
    indices = [
        Index(value = ["server_id", "user_id", "book_id"]),
        Index(value = ["book_id", "created_at"]),
    ],
)
data class AnnotationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "server_id")
    val serverId: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    val type: AnnotationType,
    @ColumnInfo(name = "selected_text")
    val selectedText: String?,
    val note: String?,
    val color: HighlightColor,
    @ColumnInfo(name = "text_href")
    val textHref: String?,
    @ColumnInfo(name = "element_id")
    val elementId: String?,
    @ColumnInfo(name = "start_offset")
    val startOffset: Int,
    @ColumnInfo(name = "end_offset")
    val endOffset: Int,
    @ColumnInfo(name = "reading_cfi")
    val readingCfi: String?,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,
    @ColumnInfo(name = "chapter_title")
    val chapterTitle: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    fun toDomain(): BookAnnotation = BookAnnotation(
        id = id,
        bookId = bookId,
        serverId = serverId,
        userId = userId,
        type = type,
        selectedText = selectedText,
        note = note,
        color = color,
        textHref = textHref,
        elementId = elementId,
        startOffset = startOffset,
        endOffset = endOffset,
        readingCfi = readingCfi,
        chapterIndex = chapterIndex,
        chapterTitle = chapterTitle,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(domain: BookAnnotation): AnnotationEntity = AnnotationEntity(
            id = domain.id,
            serverId = domain.serverId,
            userId = domain.userId,
            bookId = domain.bookId,
            type = domain.type,
            selectedText = domain.selectedText,
            note = domain.note,
            color = domain.color,
            textHref = domain.textHref,
            elementId = domain.elementId,
            startOffset = domain.startOffset,
            endOffset = domain.endOffset,
            readingCfi = domain.readingCfi,
            chapterIndex = domain.chapterIndex,
            chapterTitle = domain.chapterTitle,
            createdAt = domain.createdAt,
        )
    }
}
