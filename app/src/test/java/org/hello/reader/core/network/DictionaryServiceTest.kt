package org.hello.reader.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DictionaryServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient
    private lateinit var service: DictionaryService

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = NetworkModule.okHttpClient(
            ServerAuthInterceptor({ null }) {},
            enableLogging = false,
        )
        service = DictionaryService(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `lookup cleans punctuation and preserves display casing`() = runTest {
        val sampleJson = """
        [
          {
            "word": "hello",
            "phonetic": "/həˈloʊ/",
            "phonetics": [],
            "meanings": [
              {
                "partOfSpeech": "interjection",
                "definitions": [
                  {"definition": "\"Hello!\" or an equivalent greeting."}
                ]
              }
            ]
          }
        ]
        """.trimIndent()
        val parsed = service.parseResponsePayload("Hello", sampleJson, 200)
        assertTrue(parsed is DictionaryResult.Success)
        assertEquals("Hello", (parsed as DictionaryResult.Success).entry.word)
    }

    @Test
    fun `lookup parses real Free Dictionary API response with phonetic audio and definitions`() = runTest {
        val sampleJson = """
        [
          {
            "word": "tardis",
            "phonetic": "/ˈtɑːdɪs/",
            "phonetics": [
              {
                "text": "/ˈtɑːdɪs/",
                "audio": "https://api.dictionaryapi.dev/media/pronunciations/en/tardis-uk.mp3"
              }
            ],
            "meanings": [
              {
                "partOfSpeech": "noun",
                "definitions": [
                  {
                    "definition": "A fictional time machine and spacecraft.",
                    "example": "Bigger on the inside."
                  }
                ]
              }
            ]
          }
        ]
        """.trimIndent()

        val parsed = service.parseResponsePayload("TARDIS", sampleJson, 200)
        assertTrue(parsed is DictionaryResult.Success)
        val entry = (parsed as DictionaryResult.Success).entry
        assertEquals("TARDIS", entry.word)
        assertEquals("/ˈtɑːdɪs/", entry.phonetic)
        assertEquals("https://api.dictionaryapi.dev/media/pronunciations/en/tardis-uk.mp3", entry.audioUrl)
        assertEquals(1, entry.meanings.size)
        assertEquals("noun", entry.meanings.single().partOfSpeech)
        assertEquals("A fictional time machine and spacecraft.", entry.meanings.single().definitions.single().definition)
        assertEquals("Bigger on the inside.", entry.meanings.single().definitions.single().example)
    }

    @Test
    fun `lookup returns NotFound when dictionary service reports word not found`() = runTest {
        val notFoundJson = """{"title":"No Definitions Found","message":"Sorry pal, we couldn't find definitions."}"""
        val parsed = service.parseResponsePayload("Gallifrey", notFoundJson, 404)
        assertTrue(parsed is DictionaryResult.NotFound)
        assertEquals("Sorry pal, we couldn't find definitions.", (parsed as DictionaryResult.NotFound).message)
    }
}
