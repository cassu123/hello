package org.hello.reader.core.model

import kotlinx.serialization.Serializable

/**
 * A self-hosted Storyteller server connection. Persisted only inside the
 * encrypted [org.hello.reader.core.security.SecurityVault].
 */
@Serializable
data class ServerProfile(
    val id: String,                 // UUID; also the server_id scope key for Room and files/
    val name: String,               // Friendly name (e.g. "Home NAS")
    val baseUrl: String,            // Normalized: scheme + host [+ port] [+ path], no trailing slash
    val authToken: String,
    val refreshToken: String? = null,
    val userId: String,
    val username: String,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "Base URL must start with http:// or https://"
        }
        require(!baseUrl.endsWith("/")) {
            "Base URL must not end with a trailing slash"
        }
    }

    val serverLabel: String
        get() = name.ifBlank { baseUrl.removePrefix("https://").removePrefix("http://") }

    /** Never print credentials into logs or crash reports. */
    override fun toString(): String =
        "ServerProfile(id=$id, name=$name, baseUrl=$baseUrl, userId=$userId, " +
            "username=$username, isActive=$isActive)"
}
