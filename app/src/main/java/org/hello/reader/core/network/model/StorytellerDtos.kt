package org.hello.reader.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponseDto(
    @SerialName("status") val status: String,
    @SerialName("version") val version: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("features") val features: List<String> = emptyList(),
) {
    val isHealthy: Boolean
        get() {
            val normalized = status.trim().lowercase()
            return normalized.isNotBlank() &&
                normalized !in setOf(
                    "down",
                    "error",
                    "fail",
                    "failed",
                    "unhealthy",
                    "maintenance",
                    "migrating",
                    "degraded",
                    "offline",
                    "stopped",
                )
        }
}

@Serializable
data class LoginRequestDto(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
) {
    override fun toString(): String = "LoginRequestDto(username=$username, password=***)"
}

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    /** Optional: when the server omits it, the client resolves it via `GET /api/user/me`. */
    @SerialName("user_id") val userId: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
) {
    override fun toString(): String = "TokenResponseDto(tokenType=$tokenType, userId=$userId)"
}

@Serializable
data class UserDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
)

@Serializable
data class BookDetailResponseDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("aligned_epub_download_url") val alignedEpubDownloadUrl: String? = null,
    @SerialName("ebook_download_url") val ebookDownloadUrl: String? = null,
    @SerialName("audio_download_url") val audioDownloadUrl: String? = null,
    @SerialName("smil_manifest_url") val smilManifestUrl: String? = null,
    @SerialName("audio_stream_url") val audioStreamUrl: String? = null,
    @SerialName("sha256") val sha256: String? = null,
)

@Serializable
data class SyncCheckpointDto(
    @SerialName("book_id") val bookId: String,
    @SerialName("reading_cfi") val readingCfi: String? = null,
    @SerialName("audio_position_ms") val audioPositionMs: Long = 0L,
    @SerialName("total_duration_ms") val totalDurationMs: Long = 0L,
    @SerialName("chapter_index") val chapterIndex: Int = 0,
    @SerialName("chapter_title") val chapterTitle: String? = null,
    @SerialName("active_sentence_id") val activeSentenceId: String? = null,
    @SerialName("progress_percent") val progressPercent: Float = 0f,
    @SerialName("timestamp") val timestamp: Long = 0L,
)

@Serializable
data class SyncResponseDto(
    @SerialName("status") val status: String,
    @SerialName("updated_at") val updatedAt: Long,
)

/**
 * Storyteller v2 Readium Locator and Position sync models for `/api/v2/books/{bookId}/positions`.
 */
@Serializable
data class StorytellerPositionPayloadDto(
    @SerialName("locator") val locator: ReadiumLocatorDto,
    @SerialName("timestamp") val timestamp: Long,
)

@Serializable
data class ReadiumLocationsDto(
    @SerialName("fragments") val fragments: List<String> = emptyList(),
    @SerialName("progression") val progression: Float? = null,
    @SerialName("totalProgression") val totalProgression: Float? = null,
    @SerialName("position") val position: Int? = null,
)

@Serializable
data class ReadiumLocatorDto(
    @SerialName("href") val href: String,
    @SerialName("type") val type: String = "application/xhtml+xml",
    @SerialName("title") val title: String? = null,
    @SerialName("locations") val locations: ReadiumLocationsDto? = null,
)
