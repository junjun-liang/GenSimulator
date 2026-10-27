package com.flyme.auto.gensimulator.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateProtocolTest {
    private val json = Json

    @Test
    fun requestRoundTripsUnicodeNewlinesMarkdownAndJsonText() {
        val query = """
            帮我生成座椅加热
            # Markdown
            {"level":3}
        """.trimIndent()
        val request = CreateRequest(1, "request-1", query)

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<CreateRequest>(encoded)

        assertEquals(request, decoded)
        assertTrue(encoded.contains("request-1"))
    }

    @Test
    fun responseDeserializes() {
        val response = json.decodeFromString<CreateResponse>(
            """{"requestId":"id-1","accepted":true,"code":"INGRESS_ACCEPTED"}"""
        )
        assertEquals("id-1", response.requestId)
        assertTrue(response.accepted)
        assertEquals("INGRESS_ACCEPTED", response.code)
    }
}
