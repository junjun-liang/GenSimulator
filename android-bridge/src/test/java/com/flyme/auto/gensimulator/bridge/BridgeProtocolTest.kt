package com.flyme.auto.gensimulator.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
    @Test
    fun healthContainsV1V2AndDiagnostics() {
        val payload = BridgeProtocol.health(FakeIngress())
        val decoded = FlatJson.parse(payload.json)
        assertEquals(200, payload.status)
        assertEquals("ready", decoded["bridge"])
        assertEquals(1L, decoded["protocolVersion"])
        assertEquals(2L, decoded["contractVersion"])
        assertEquals(true, decoded["sameSigner"])
        assertEquals("CONNECTED", decoded["binderState"])
    }

    @Test
    fun createPreservesUnicodeNewlinesAndEchoesRequestId() {
        val ingress = FakeIngress()
        val query = "打开主驾座椅加热\n# markdown\n{\"level\":3}"
        val body = FlatJson.encode(
            linkedMapOf(
                "protocolVersion" to 1,
                "requestId" to "request-1",
                "query" to query,
            )
        )
        val response = BridgeProtocol.create(body, ingress)
        val decoded = FlatJson.parse(response.json)
        assertEquals(query, ingress.lastQuery)
        assertEquals("request-1", decoded["requestId"])
        assertEquals(true, decoded["accepted"])
        assertEquals("INGRESS_ACCEPTED", decoded["code"])
    }

    @Test
    fun protocolMismatchIsRejectedWithoutCallingIngress() {
        val ingress = FakeIngress()
        val response = BridgeProtocol.create(
            "{\"protocolVersion\":9,\"requestId\":\"id\",\"query\":\"query\"}",
            ingress,
        )
        val decoded = FlatJson.parse(response.json)
        assertEquals(false, decoded["accepted"])
        assertEquals("PROTOCOL_VERSION_MISMATCH", decoded["code"])
        assertEquals(null, ingress.lastQuery)
    }

    @Test
    fun blankAndOversizedQueryAreRejected() {
        val blank = FlatJson.parse(
            BridgeProtocol.create(
                "{\"protocolVersion\":1,\"requestId\":\"blank\",\"query\":\"  \"}",
                FakeIngress(),
            ).json
        )
        assertEquals("QUERY_EMPTY", blank["code"])

        val huge = "x".repeat(BridgeProtocol.MAX_QUERY_CHARS + 1)
        val response = BridgeProtocol.create(
            FlatJson.encode(mapOf("protocolVersion" to 1, "requestId" to "huge", "query" to huge)),
            FakeIngress(),
        )
        assertEquals("QUERY_TOO_LARGE", FlatJson.parse(response.json)["code"])
    }

    @Test
    fun acceptedRejectedAndBinderFailuresMapToStableWireCodes() {
        val accepted = FlatJson.parse(createWith(IngressResult.Accepted))
        val rejected = FlatJson.parse(createWith(IngressResult.Rejected))
        val denied = FlatJson.parse(createWith(IngressResult.Failure(BridgeErrorCode.GENCARDS_PERMISSION_DENIED)))
        val died = FlatJson.parse(createWith(IngressResult.Failure(BridgeErrorCode.GENCARDS_BINDER_DIED)))
        val bindFailed = FlatJson.parse(
            createWith(IngressResult.Failure(BridgeErrorCode.GENCARDS_BIND_FAILED))
        )
        val connectTimeout = FlatJson.parse(
            createWith(IngressResult.Failure(BridgeErrorCode.GENCARDS_CONNECT_TIMEOUT))
        )

        assertTrue(accepted["accepted"] as Boolean)
        assertEquals("INGRESS_ACCEPTED", accepted["code"])
        assertFalse(rejected["accepted"] as Boolean)
        assertEquals("INGRESS_REJECTED", rejected["code"])
        assertEquals("GENCARDS_PERMISSION_DENIED", denied["code"])
        assertEquals("GENCARDS_BINDER_DIED", died["code"])
        assertEquals("GENCARDS_BIND_FAILED", bindFailed["code"])
        assertEquals("GENCARDS_CONNECT_TIMEOUT", connectTimeout["code"])
    }

    @Test
    fun binderStateMapsToExpectedFailureCode() {
        assertEquals(BridgeErrorCode.GENCARDS_NOT_INSTALLED, BinderConnectionState.GENCARDS_NOT_INSTALLED.failureCode())
        assertEquals(BridgeErrorCode.GENCARDS_PERMISSION_DENIED, BinderConnectionState.PERMISSION_DENIED.failureCode())
        assertEquals(BridgeErrorCode.GENCARDS_BINDER_DIED, BinderConnectionState.BINDER_DIED.failureCode())
        assertEquals(BridgeErrorCode.GENCARDS_BIND_FAILED, BinderConnectionState.DISCONNECTED.failureCode())
    }

    @Test
    fun createSubmitsToIngressExactlyOnce() {
        val ingress = FakeIngress()
        BridgeProtocol.create(
            "{\"protocolVersion\":1,\"requestId\":\"id\",\"query\":\"打开第一排空调\"}",
            ingress,
        )
        assertEquals(1, ingress.submitCount)
        assertEquals("打开第一排空调", ingress.lastQuery)
    }

    private fun createWith(result: IngressResult): String {
        val ingress = FakeIngress(result)
        return BridgeProtocol.create(
            "{\"protocolVersion\":1,\"requestId\":\"id\",\"query\":\"query\"}",
            ingress,
        ).json
    }

    private class FakeIngress(
        private val result: IngressResult = IngressResult.Accepted,
    ) : CreateIngress {
        var lastQuery: String? = null
        var submitCount: Int = 0

        override fun snapshot() = BridgeHealthSnapshot(
            genCardsInstalled = true,
            binderConnected = true,
            contractVersion = 2,
            sameSigner = true,
            binderState = "CONNECTED",
            lastErrorCode = null,
        )

        override fun submit(query: String): IngressResult {
            submitCount += 1
            lastQuery = query
            return result
        }
    }
}
