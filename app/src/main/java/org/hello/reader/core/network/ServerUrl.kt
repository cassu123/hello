package org.hello.reader.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalizes user-entered Storyteller server addresses into the canonical
 * `ServerProfile.baseUrl` form: lowercase scheme and host, explicit http(s)
 * scheme, optional sub-path, no trailing slash, no query or fragment.
 */
object ServerUrl {

    sealed interface Result {
        data class Valid(val baseUrl: String) : Result
        data class Invalid(val reason: Reason) : Result
    }

    /** Why an address was rejected; the UI words each one. */
    enum class Reason { EMPTY, CONTAINS_SPACES, UNSUPPORTED_SCHEME, MALFORMED, QUERY_OR_FRAGMENT, CREDENTIALS }

    fun normalize(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (trimmed.any { it.isWhitespace() }) return Result.Invalid(Reason.CONTAINS_SPACES)

        val hasScheme = trimmed.contains("://")
        if (hasScheme) {
            val scheme = trimmed.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") {
                return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
            }
        }
        val withScheme = if (hasScheme) trimmed else "https://$trimmed"

        val url = withScheme.toHttpUrlOrNull() ?: return Result.Invalid(Reason.MALFORMED)
        if (url.query != null || url.fragment != null) {
            return Result.Invalid(Reason.QUERY_OR_FRAGMENT)
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Result.Invalid(Reason.CREDENTIALS)
        }

        // HttpUrl already lowercases scheme/host and drops default ports.
        val path = url.encodedPath.trimEnd('/')
        val origin = url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        return Result.Valid(origin + path)
    }

    fun isCleartext(baseUrl: String): Boolean = baseUrl.startsWith("http://")
}
