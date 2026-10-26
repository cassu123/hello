package org.hello.reader.feature.onboarding.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QrConnectPayloadTest {

    @Test
    fun `parses JSON payload and normalizes url`() {
        val payload = QrConnectPayload.parse("""{"url":"https://books.example.com/","token":"abc.def"}""")
        assertEquals(QrConnectPayload("https://books.example.com", "abc.def"), payload)
    }

    @Test
    fun `parses JSON payload with extra fields`() {
        val payload = QrConnectPayload.parse("""{"url":"http://10.0.0.5:8001","token":"t","v":2}""")
        assertEquals(QrConnectPayload("http://10.0.0.5:8001", "t"), payload)
    }

    @Test
    fun `parses deep link with percent-encoded params`() {
        val payload = QrConnectPayload.parse(
            "hello://connect?url=https%3A%2F%2Fexample.com%2Fstoryteller&token=a%2Bb%3D%3D",
        )
        assertEquals(QrConnectPayload("https://example.com/storyteller", "a+b=="), payload)
    }

    @Test
    fun `rejects unknown or incomplete payloads`() {
        listOf(
            "https://example.com",
            "hello://other?url=https%3A%2F%2Fexample.com&token=t",
            "hello://connect?url=https%3A%2F%2Fexample.com",
            "hello://connect?token=t",
            """{"url":"https://example.com"}""",
            """{"url":"https://example.com","token":"   "}""",
            """{"url":"ftp://example.com","token":"t"}""",
            """{not json""",
            "",
        ).forEach { assertNull("expected null for '$it'", QrConnectPayload.parse(it)) }
    }

    @Test
    fun `toString does not leak token`() {
        assertFalse(QrConnectPayload("https://x.example", "secret-token").toString().contains("secret-token"))
    }
}
