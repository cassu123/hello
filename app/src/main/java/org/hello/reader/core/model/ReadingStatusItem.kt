package org.hello.reader.core.model

import kotlinx.serialization.Serializable

/**
 * Storyteller reading status (e.g. "To read", "Reading", "Read").
 */
@Serializable
data class ReadingStatusItem(
    val uuid: String,
    val name: String,
    val label: String,
    val description: String? = null,
    val isServerStatus: Boolean = false,
) {
    companion object {
        val DEFAULT_STATUSES = listOf(
            ReadingStatusItem(uuid = "to-read", name = "to_read", label = "To read", isServerStatus = false),
            ReadingStatusItem(uuid = "reading", name = "reading", label = "Reading", isServerStatus = false),
            ReadingStatusItem(uuid = "read", name = "read", label = "Read", isServerStatus = false),
        )
    }
}
