package org.hello.reader.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp interceptor that routes every Retrofit call to a Storyteller server
 * chosen at runtime, so a single Retrofit instance serves all profiles.
 *
 * 1. Requests built against [PLACEHOLDER_BASE_URL] are rebased onto the active
 *    profile (or onto [HEADER_TARGET_BASE_URL] when present): scheme, host and
 *    port are replaced and the server's base path is prepended, so servers
 *    hosted under a sub-path such as `https://example.com/storyteller` work.
 *    Absolute URLs on real hosts pass through unchanged.
 * 2. Injects `Authorization: Bearer <token>` from the active profile only when
 *    the final URL is on that profile's origin and the request neither opts out
 *    via [HEADER_NO_AUTH] nor carries its own Authorization header. The token is
 *    never sent to any other host.
 * 3. Reports 401 responses to [onAuthExpired], but only for requests that were
 *    authenticated with the active profile's token.
 *
 * Internal routing headers are stripped before the request leaves the device.
 */
class ServerAuthInterceptor(
    private val profileProvider: DynamicBaseUrlProvider,
    private val onAuthExpired: (profileId: String) -> Unit,
) : Interceptor {

    companion object {
        /** Marks an endpoint that must never receive the active profile's token. */
        const val HEADER_NO_AUTH = "X-Hello-No-Auth"

        /**
         * Routes a single request to an explicit base URL instead of the active
         * profile (used during onboarding, before a profile exists). Such requests
         * never receive the active profile's token.
         */
        const val HEADER_TARGET_BASE_URL = "X-Hello-Base-Url"

        /**
         * Retrofit requires a fixed base URL; every request is rewritten away
         * from it. `.invalid` is reserved (RFC 2606) and can never resolve.
         */
        const val PLACEHOLDER_BASE_URL = "http://storyteller.invalid/"

        private const val HEADER_AUTHORIZATION = "Authorization"
    }

    private val placeholderHost = PLACEHOLDER_BASE_URL.toHttpUrl().host

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val explicitTarget = original.header(HEADER_TARGET_BASE_URL)
        val profile = if (explicitTarget == null) profileProvider.getActiveProfile() else null
        val profileBase = profile?.let {
            it.baseUrl.toHttpUrlOrNull()
                ?: throw IOException("Invalid base URL in active ServerProfile: ${it.baseUrl}")
        }

        val finalUrl = if (original.url.host == placeholderHost) {
            val base = when {
                explicitTarget != null -> explicitTarget.toHttpUrlOrNull()
                    ?: throw IOException("Invalid target server URL: $explicitTarget")
                profileBase != null -> profileBase
                else -> throw NoActiveServerException()
            }
            rebase(original.url, base)
        } else {
            original.url
        }

        val builder = original.newBuilder()
            .url(finalUrl)
            .removeHeader(HEADER_NO_AUTH)
            .removeHeader(HEADER_TARGET_BASE_URL)

        val authToken = profile?.authToken
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { profileBase != null && finalUrl.isWithinServerScope(profileBase) }
            ?.takeIf { original.header(HEADER_NO_AUTH) == null }
            ?.takeIf { original.header(HEADER_AUTHORIZATION) == null }
        if (authToken != null) {
            builder.header(HEADER_AUTHORIZATION, "Bearer $authToken")
        }

        val response = chain.proceed(builder.build())
        if (response.code == 401 && authToken != null) {
            onAuthExpired(profile.id)
        }
        return response
    }

    private fun rebase(url: HttpUrl, base: HttpUrl): HttpUrl {
        val builder = url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath("/")
        (base.encodedPathSegments + url.encodedPathSegments)
            .filter { it.isNotEmpty() }
            .forEach { builder.addEncodedPathSegment(it) }
        return builder.build()
    }

    private fun HttpUrl.isWithinServerScope(serverBase: HttpUrl): Boolean {
        if (scheme != serverBase.scheme || host != serverBase.host || port != serverBase.port) {
            return false
        }
        val basePath = serverBase.encodedPath.trimEnd('/')
        if (basePath.isEmpty()) return true
        return encodedPath == basePath || encodedPath.startsWith("$basePath/")
    }
}

/** Thrown when a profile-scoped request is made before any server is configured. */
class NoActiveServerException : IOException("No active Storyteller server is configured")
