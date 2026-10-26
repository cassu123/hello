package org.hello.reader.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class HighlightColor(val displayName: String, val hex: String) {
    YELLOW("Yellow", "#FFF59D"),
    GREEN("Green", "#C8E6C9"),
    BLUE("Blue", "#BBDEFB"),
    PINK("Pink", "#F8BBD0"),
}

@Serializable
enum class AnnotationType {
    HIGHLIGHT,
    BOOKMARK,
}

@Serializable
data class BookAnnotation(
    val id: String,
    val bookId: String,
    val serverId: String,
    val userId: String,
    val type: AnnotationType,
    val selectedText: String?,
    val note: String? = null,
    val color: HighlightColor = HighlightColor.YELLOW,
    val textHref: String? = null,
    val elementId: String? = null,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val readingCfi: String? = null,
    val chapterIndex: Int = 0,
    val chapterTitle: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
