package org.hello.reader.core.data

import org.hello.reader.core.model.ServerProfile
import org.hello.reader.core.network.StorytellerApi
import org.hello.reader.core.network.model.HealthResponseDto
import org.hello.reader.core.network.model.LoginRequestDto
import org.hello.reader.core.network.model.UserDto
import org.hello.reader.core.security.SecurityVault
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

sealed interface ConnectionResult<out T> {
    data class Success<T>(val value: T) : ConnectionResult<T>
    data class Failure(val error: ConnectionError) : ConnectionResult<Nothing>
}

sealed interface ConnectionError {
    data object HostNotFound : ConnectionError
    data object Unreachable : ConnectionError
    data object Timeout : ConnectionError
    data object TlsFailure : ConnectionError
    data object NotStoryteller : ConnectionError
    data class Unhealthy(val status: String) : ConnectionError
    data object InvalidCredentials : ConnectionError
    data object InvalidToken : ConnectionError
    data class Http(val code: Int) : ConnectionError
    data class Network(val detail: String?) : ConnectionError

    val message: String
        get() = when (this) {
            HostNotFound -> "Couldn't find that server. Check the address."
            Unreachable -> "The server refused the connection. Is Storyteller running?"
            Timeout -> "The server took too long to respond."
            TlsFailure -> "Secure connection failed. Check the server's HTTPS certificate."
            NotStoryteller -> "That address responded, but it isn't a Storyteller server."
            is Unhealthy -> "The server reported status \"$status\"."
            InvalidCredentials -> "Incorrect username or password."
            InvalidToken -> "This connect code is invalid or has expired."
            is Http -> "The server returned an error (HTTP $code)."
            is Network -> detail?.let { "Network error: $it" } ?: "Network error."
        }
}

/**
 * Onboarding operations against servers that may not be saved yet. Every
 * request targets the candidate URL explicitly, so the active profile's token
 * is never sent to the new server.
 */
class ServerConnectionRepository(
    private val api: StorytellerApi,
    private val vault: SecurityVault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun checkHealth(baseUrl: String): ConnectionResult<HealthResponseDto> = call {
        val response = api.checkHealth(targetBaseUrl = baseUrl)
        val body = response.body()
        when {
            response.code() == 404 -> ConnectionResult.Failure(ConnectionError.NotStoryteller)
            !response.isSuccessful -> ConnectionResult.Failure(ConnectionError.Http(response.code()))
            body == null -> ConnectionResult.Failure(ConnectionError.NotStoryteller)
            !body.isHealthy -> ConnectionResult.Failure(ConnectionError.Unhealthy(body.status))
            else -> ConnectionResult.Success(body)
        }
    }

    /** Exchanges credentials for a token and saves the result as the active profile. */
    suspend fun login(baseUrl: String, username: String, password: String): ConnectionResult<ServerProfile> = call {
        val jsonResponse = api.login(LoginRequestDto(username, password), targetBaseUrl = baseUrl)
        val response = if (jsonResponse.code() == 415 || jsonResponse.code() == 422 || jsonResponse.code() == 500) {
            api.loginForm(username, password, targetBaseUrl = baseUrl)
        } else {
            jsonResponse
        }
        val token = response.body()
        when {
            response.code() == 400 || response.code() == 401 || response.code() == 403 ->
                ConnectionResult.Failure(ConnectionError.InvalidCredentials)
            !response.isSuccessful -> ConnectionResult.Failure(ConnectionError.Http(response.code()))
            token == null || token.accessToken.isBlank() -> ConnectionResult.Failure(ConnectionError.NotStoryteller)
            token.userId != null -> ConnectionResult.Success(
                persist(baseUrl, token.accessToken, token.refreshToken, UserDto(token.userId, username)),
            )
            else -> when (val user = fetchUser(baseUrl, token.accessToken)) {
                is ConnectionResult.Success -> ConnectionResult.Success(
                    persist(baseUrl, token.accessToken, token.refreshToken, user.value),
                )
                is ConnectionResult.Failure -> {
                    // Upstream Storyteller endpoints may lack /api/user/me.
                    // Recover gracefully by extracting JWT 'sub' claim or falling back to username.
                    val fallbackId = extractUserIdFromJwt(token.accessToken) ?: username
                    ConnectionResult.Success(
                        persist(baseUrl, token.accessToken, token.refreshToken, UserDto(fallbackId, username)),
                    )
                }
            }
        }
    }

    /** Validates a token obtained out-of-band (QR quick-connect) and saves it as the active profile. */
    suspend fun connectWithToken(baseUrl: String, token: String): ConnectionResult<ServerProfile> = call {
        when (val user = fetchUser(baseUrl, token)) {
            is ConnectionResult.Success -> ConnectionResult.Success(persist(baseUrl, token, null, user.value))
            is ConnectionResult.Failure -> {
                val fallbackId = extractUserIdFromJwt(token)
                if (fallbackId != null) {
                    ConnectionResult.Success(persist(baseUrl, token, null, UserDto(fallbackId, fallbackId)))
                } else {
                    user
                }
            }
        }
    }

    internal fun extractUserIdFromJwt(token: String): String? = runCatching {
        val parts = token.split(".")
        if (parts.size < 2) return null
        val payload = parts[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decodedBytes = try {
            java.util.Base64.getUrlDecoder().decode(padded)
        } catch (_: Exception) {
            try {
                java.util.Base64.getDecoder().decode(padded)
            } catch (_: Exception) {
                null
            }
        } ?: return null
        val json = String(decodedBytes, Charsets.UTF_8)
        val subMatch = Regex("""\"sub\"\s*:\s*\"([^\"]+)\"""").find(json)?.groupValues?.get(1)
            ?: Regex("""\"user_id\"\s*:\s*\"([^\"]+)\"""").find(json)?.groupValues?.get(1)
            ?: Regex("""\"id\"\s*:\s*\"([^\"]+)\"""").find(json)?.groupValues?.get(1)
            ?: Regex("""\"sub\"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)
        subMatch?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun fetchUser(baseUrl: String, token: String): ConnectionResult<UserDto> {
        val response: Response<UserDto> = api.getCurrentUser(targetBaseUrl = baseUrl, authorization = "Bearer $token")
        val body = response.body()
        return when {
            response.code() == 401 || response.code() == 403 -> ConnectionResult.Failure(ConnectionError.InvalidToken)
            !response.isSuccessful -> ConnectionResult.Failure(ConnectionError.Http(response.code()))
            body == null -> ConnectionResult.Failure(ConnectionError.NotStoryteller)
            else -> ConnectionResult.Success(body)
        }
    }

    /**
     * Re-authenticating the same user on the same server reuses the existing
     * profile id, so locally cached data scoped by server_id stays attached.
     */
    private suspend fun persist(baseUrl: String, token: String, refreshToken: String?, user: UserDto): ServerProfile {
        val existing = vault.getAllProfiles().firstOrNull { it.baseUrl == baseUrl && it.userId == user.id }
        val now = clock()
        val profile = existing?.copy(
            authToken = token,
            refreshToken = refreshToken,
            username = user.username,
            isActive = true,
            lastUsedAt = now,
        ) ?: ServerProfile(
            id = newId(),
            name = baseUrl.toHttpUrl().host,
            baseUrl = baseUrl,
            authToken = token,
            refreshToken = refreshToken,
            userId = user.id,
            username = user.username,
            isActive = true,
            createdAt = now,
            lastUsedAt = now,
        )
        vault.saveProfile(profile)
        return profile
    }

    private inline fun <T> call(block: () -> ConnectionResult<T>): ConnectionResult<T> = try {
        block()
    } catch (e: UnknownHostException) {
        ConnectionResult.Failure(ConnectionError.HostNotFound)
    } catch (e: ConnectException) {
        ConnectionResult.Failure(ConnectionError.Unreachable)
    } catch (e: SocketTimeoutException) {
        ConnectionResult.Failure(ConnectionError.Timeout)
    } catch (e: SSLException) {
        ConnectionResult.Failure(ConnectionError.TlsFailure)
    } catch (e: SerializationException) {
        ConnectionResult.Failure(ConnectionError.NotStoryteller)
    } catch (e: IOException) {
        ConnectionResult.Failure(ConnectionError.Network(e.message))
    }
}
