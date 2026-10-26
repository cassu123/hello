package org.hello.reader.feature.onboarding.qr

import org.hello.reader.core.network.ServerUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** A server + token pair decoded from a Storyteller quick-connect QR code. */
data class QrConnectPayload(val baseUrl: String, val token: String) {
    override fun toString(): String = "QrConnectPayload(baseUrl=$baseUrl, token=***)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class JsonForm(
            @SerialName("url") val url: String,
            @SerialName("token") val token: String,
        )

        /**
         * Accepts either `{"url": "...", "token": "..."}` or
         * `hello://connect?url=<encoded>&token=<encoded>`. Returns null for
         * anything else, including payloads with an invalid server URL.
         */
        fun parse(raw: String): QrConnectPayload? {
            val text = raw.trim()
            val (url, token) = when {
                text.startsWith("{") -> parseJson(text)
                text.startsWith("hello://", ignoreCase = true) -> parseDeepLink(text)
                else -> null
            } ?: return null

            if (token.isBlank()) return null
            val normalized = ServerUrl.normalize(url) as? ServerUrl.Result.Valid ?: return null
            return QrConnectPayload(normalized.baseUrl, token.trim())
        }

        private fun parseJson(text: String): Pair<String, String>? = try {
            json.decodeFromString(JsonForm.serializer(), text).let { it.url to it.token }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun parseDeepLink(text: String): Pair<String, String>? {
            val uri = try {
                URI(text)
            } catch (e: URISyntaxException) {
                return null
            }
            if (!uri.host.equals("connect", ignoreCase = true)) return null
            val params = uri.rawQuery.orEmpty()
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', missingDelimiterValue = "")
                    decode(key) to decode(value)
                }
            val url = params["url"] ?: return null
            val token = params["token"] ?: return null
            return url to token
        }

        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}
