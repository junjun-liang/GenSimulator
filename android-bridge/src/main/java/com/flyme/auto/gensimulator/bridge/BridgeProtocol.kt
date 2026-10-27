package com.flyme.auto.gensimulator.bridge

internal object BridgeErrorCode {
    const val INGRESS_ACCEPTED = "INGRESS_ACCEPTED"
    const val INGRESS_REJECTED = "INGRESS_REJECTED"
    const val GENCARDS_NOT_INSTALLED = "GENCARDS_NOT_INSTALLED"
    const val GENCARDS_BIND_FAILED = "GENCARDS_BIND_FAILED"
    const val GENCARDS_PERMISSION_DENIED = "GENCARDS_PERMISSION_DENIED"
    const val GENCARDS_BINDER_DIED = "GENCARDS_BINDER_DIED"
    const val GENCARDS_CONNECT_TIMEOUT = "GENCARDS_CONNECT_TIMEOUT"
    const val PROTOCOL_VERSION_MISMATCH = "PROTOCOL_VERSION_MISMATCH"
    const val QUERY_EMPTY = "QUERY_EMPTY"
    const val QUERY_TOO_LARGE = "QUERY_TOO_LARGE"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

internal data class BridgeHealthSnapshot(
    val genCardsInstalled: Boolean,
    val binderConnected: Boolean,
    val contractVersion: Int,
    val sameSigner: Boolean?,
    val binderState: String,
    val lastErrorCode: String?,
)

internal sealed interface IngressResult {
    data object Accepted : IngressResult
    data object Rejected : IngressResult
    data class Failure(val code: String) : IngressResult
}

internal interface CreateIngress {
    fun snapshot(): BridgeHealthSnapshot
    fun submit(query: String): IngressResult
}

internal data class HttpPayload(
    val status: Int = 200,
    val json: String,
)

internal object BridgeProtocol {
    const val PROTOCOL_VERSION = 1
    const val MAX_QUERY_CHARS = 60_000

    fun health(ingress: CreateIngress): HttpPayload {
        val health = ingress.snapshot()
        val fields = linkedMapOf<String, Any?>(
            "bridge" to "ready",
            "genCardsInstalled" to health.genCardsInstalled,
            "binderConnected" to health.binderConnected,
            "contractVersion" to health.contractVersion,
            "protocolVersion" to PROTOCOL_VERSION,
            "sameSigner" to health.sameSigner,
            "binderState" to health.binderState,
            "lastErrorCode" to health.lastErrorCode,
        )
        return HttpPayload(json = FlatJson.encode(fields))
    }

    fun create(body: String, ingress: CreateIngress): HttpPayload {
        val parsed = runCatching { FlatJson.parse(body) }.getOrElse {
            return createResponse("", false, BridgeErrorCode.INVALID_REQUEST)
        }
        val requestId = parsed["requestId"] as? String ?: ""
        val protocolVersion = (parsed["protocolVersion"] as? Number)?.toInt()
        val query = parsed["query"] as? String

        if (requestId.isBlank() || requestId.length > 256 || query == null) {
            return createResponse(requestId, false, BridgeErrorCode.INVALID_REQUEST)
        }
        if (protocolVersion != PROTOCOL_VERSION) {
            return createResponse(requestId, false, BridgeErrorCode.PROTOCOL_VERSION_MISMATCH)
        }
        if (query.trim().isEmpty()) {
            return createResponse(requestId, false, BridgeErrorCode.QUERY_EMPTY)
        }
        if (query.length > MAX_QUERY_CHARS) {
            return createResponse(requestId, false, BridgeErrorCode.QUERY_TOO_LARGE)
        }

        return when (val result = ingress.submit(query)) {
            IngressResult.Accepted -> createResponse(requestId, true, BridgeErrorCode.INGRESS_ACCEPTED)
            IngressResult.Rejected -> createResponse(requestId, false, BridgeErrorCode.INGRESS_REJECTED)
            is IngressResult.Failure -> createResponse(requestId, false, result.code)
        }
    }

    private fun createResponse(requestId: String, accepted: Boolean, code: String): HttpPayload =
        HttpPayload(
            json = FlatJson.encode(
                linkedMapOf(
                    "requestId" to requestId,
                    "accepted" to accepted,
                    "code" to code,
                )
            )
        )
}

/** Minimal flat JSON codec for the bridge's small, versioned localhost protocol. */
internal object FlatJson {
    fun parse(text: String): Map<String, Any?> = Parser(text).parseObject()

    fun encode(values: Map<String, Any?>): String = buildString {
        append('{')
        values.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendQuoted(key)
            append(':')
            appendValue(value)
        }
        append('}')
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean, is Number -> append(value.toString())
            else -> error("Unsupported JSON value type: ${value::class.java.name}")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parseObject(): Map<String, Any?> {
            skipWhitespace()
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                finish()
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when (val next = next()) {
                    ',' -> Unit
                    '}' -> {
                        finish()
                        return result
                    }
                    else -> error("Expected ',' or '}' but found '$next'")
                }
            }
        }

        private fun parseValue(): Any? = when (peek()) {
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unsupported JSON value at index $index")
        }

        private fun parseString(): String {
            expect('"')
            return buildString {
                while (index < source.length) {
                    val char = next()
                    when (char) {
                        '"' -> return@buildString
                        '\\' -> {
                            val escaped = next()
                            append(
                                when (escaped) {
                                    '"', '\\', '/' -> escaped
                                    'b' -> '\b'
                                    'f' -> '\u000C'
                                    'n' -> '\n'
                                    'r' -> '\r'
                                    't' -> '\t'
                                    'u' -> parseUnicode()
                                    else -> error("Invalid escape \\$escaped")
                                }
                            )
                        }
                        else -> append(char)
                    }
                }
                error("Unterminated JSON string")
            }
        }

        private fun parseUnicode(): Char {
            require(index + 4 <= source.length) { "Incomplete unicode escape" }
            val hex = source.substring(index, index + 4)
            index += 4
            return hex.toInt(16).toChar()
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek() == '-') index++
            while (index < source.length && source[index].isDigit()) index++
            val hasFraction = if (index < source.length && source[index] == '.') {
                index++
                while (index < source.length && source[index].isDigit()) index++
                true
            } else false
            val value = source.substring(start, index)
            return if (hasFraction) value.toDouble() else value.toLong()
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            require(source.startsWith(literal, index)) { "Expected $literal" }
            index += literal.length
            return value
        }

        private fun finish() {
            skipWhitespace()
            require(index == source.length) { "Trailing JSON content" }
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun expect(expected: Char) {
            val actual = next()
            require(actual == expected) { "Expected '$expected' but found '$actual'" }
        }

        private fun peek(): Char {
            require(index < source.length) { "Unexpected end of JSON" }
            return source[index]
        }

        private fun next(): Char {
            require(index < source.length) { "Unexpected end of JSON" }
            return source[index++]
        }
    }
}
