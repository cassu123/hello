package org.hello.reader.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class DictionaryDefinition(
    val definition: String,
    val example: String? = null,
)

@Serializable
data class DictionaryMeaning(
    val partOfSpeech: String,
    val definitions: List<DictionaryDefinition> = emptyList(),
)

@Serializable
data class DictionaryEntry(
    val word: String,
    val phonetic: String? = null,
    val audioUrl: String? = null,
    val meanings: List<DictionaryMeaning> = emptyList(),
)

sealed interface DictionaryResult {
    data class Success(val entry: DictionaryEntry) : DictionaryResult
    data class NotFound(val word: String, val message: String? = null) : DictionaryResult
    data class NetworkError(val message: String) : DictionaryResult
}

// Raw DTOs matching api.dictionaryapi.dev/api/v2/entries/en/<word>
@Serializable
internal data class ApiPhoneticDto(
    val text: String? = null,
    val audio: String? = null,
)

@Serializable
internal data class ApiDefinitionDto(
    val definition: String,
    val example: String? = null,
)

@Serializable
internal data class ApiMeaningDto(
    val partOfSpeech: String,
    val definitions: List<ApiDefinitionDto> = emptyList(),
)

@Serializable
internal data class ApiEntryDto(
    val word: String,
    val phonetic: String? = null,
    val phonetics: List<ApiPhoneticDto> = emptyList(),
    val meanings: List<ApiMeaningDto> = emptyList(),
)

@Serializable
internal data class ApiNotFoundDto(
    val title: String? = null,
    val message: String? = null,
)

/**
 * Kindle-style Dictionary lookup client using the Free Dictionary API.
 * Lookups occur strictly on explicit user request (e.g. tapping 'Define' in the selection menu).
 *
 * Note: The free dictionary endpoint (`api.dictionaryapi.dev/api/v2/entries/en/`) currently supports
 * English vocabulary; non-English queries will return [DictionaryResult.NotFound].
 */
class DictionaryService(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun lookup(rawWord: String): DictionaryResult = withContext(Dispatchers.IO) {
        val displayWord = rawWord.trim().trim { !it.isLetter() }
        val lookupTerm = displayWord.lowercase()
        if (lookupTerm.isEmpty()) {
            return@withContext DictionaryResult.NotFound(rawWord, "Please select a valid word.")
        }

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.dictionaryapi.dev")
            .addPathSegment("api")
            .addPathSegment("v2")
            .addPathSegment("entries")
            .addPathSegment("en")
            .addPathSegment(lookupTerm)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                parseResponsePayload(displayWord, body, response.code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DictionaryResult.NetworkError(e.message ?: "Unable to connect to dictionary service.")
        } catch (e: Exception) {
            DictionaryResult.NetworkError("Failed to parse dictionary response.")
        }
    }

    internal fun parseResponsePayload(displayWord: String, body: String, code: Int): DictionaryResult {
        return when (code) {
            200 -> {
                val parsed = json.decodeFromString<List<ApiEntryDto>>(body)
                val primary = parsed.firstOrNull()
                    ?: return DictionaryResult.NotFound(displayWord)

                val audio = primary.phonetics.firstOrNull { !it.audio.isNullOrBlank() }?.audio
                val phonetic = primary.phonetic
                    ?: primary.phonetics.firstOrNull { !it.text.isNullOrBlank() }?.text

                val meanings = primary.meanings.map { m ->
                    DictionaryMeaning(
                        partOfSpeech = m.partOfSpeech,
                        definitions = m.definitions.map {
                            DictionaryDefinition(it.definition, it.example)
                        },
                    )
                }

                DictionaryResult.Success(
                    DictionaryEntry(
                        word = displayWord,
                        phonetic = phonetic,
                        audioUrl = audio?.takeIf { it.startsWith("http") },
                        meanings = meanings,
                    ),
                )
            }
            404 -> {
                val errorPayload = runCatching {
                    json.decodeFromString<ApiNotFoundDto>(body)
                }.getOrNull()
                DictionaryResult.NotFound(displayWord, errorPayload?.message)
            }
            else -> DictionaryResult.NetworkError("Dictionary service returned HTTP $code")
        }
    }
}
