package org.hello.reader.testing

import org.hello.reader.core.model.ServerProfile

fun profile(
    id: String,
    baseUrl: String = "https://$id.example.com",
    token: String = "token-$id",
    isActive: Boolean = false,
    lastUsedAt: Long = 0L,
    userId: String = "user-$id",
) = ServerProfile(
    id = id,
    name = id,
    baseUrl = baseUrl,
    authToken = token,
    userId = userId,
    username = "reader-$id",
    isActive = isActive,
    createdAt = 0L,
    lastUsedAt = lastUsedAt,
)
