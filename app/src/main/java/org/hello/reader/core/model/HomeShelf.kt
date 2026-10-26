package org.hello.reader.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class HomeShelfType {
    CONTINUE_READING,
    CURRENTLY_READING,
    TO_READ,
    RECENTLY_ADDED,
    COMPLETED,
    SERIES,
    COLLECTIONS,
    SERVER_SECTION,
}

@Serializable
data class HomeShelfConfig(
    val id: String,
    val title: String,
    val type: HomeShelfType,
    val isEnabled: Boolean = true,
    val serverSectionId: String? = null,
    val bookIds: List<String> = emptyList(),
    val selectedIds: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT_SHELVES = listOf(
            HomeShelfConfig(id = "continue_reading", title = "Continue reading", type = HomeShelfType.CONTINUE_READING),
            HomeShelfConfig(id = "currently_reading", title = "Reading", type = HomeShelfType.CURRENTLY_READING),
            HomeShelfConfig(id = "to_read", title = "To read", type = HomeShelfType.TO_READ),
            HomeShelfConfig(id = "recently_added", title = "Recently added", type = HomeShelfType.RECENTLY_ADDED),
            HomeShelfConfig(id = "completed", title = "Read", type = HomeShelfType.COMPLETED),
            HomeShelfConfig(id = "series", title = "Series", type = HomeShelfType.SERIES),
            HomeShelfConfig(id = "collections", title = "Collections", type = HomeShelfType.COLLECTIONS),
        )
    }
}
