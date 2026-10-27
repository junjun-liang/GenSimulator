package com.flyme.auto.gensimulator.bridge

import com.flyme.auto.gensimulator.config.SimulatorConfig
import com.flyme.auto.gensimulator.protocol.BridgeHealth
import com.flyme.auto.gensimulator.protocol.CreateRequest
import com.flyme.auto.gensimulator.protocol.CreateResponse
import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode
import com.flyme.auto.gensimulator.protocol.SimulatorException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.UUID

class HttpBridgeClient(
    private val config: SimulatorConfig,
    private val client: HttpClient = defaultHttpClient(),
) : BridgeClient {

    private val baseUrl: String get() = "http://127.0.0.1:${config.bridgePort}"

    override suspend fun health(): BridgeHealth = try {
        client.get("$baseUrl/health").body()
    } catch (exception: SimulatorException) {
        throw exception
    } catch (exception: Exception) {
        throw SimulatorException(
            SimulatorErrorCode.BRIDGE_UNREACHABLE,
            "Bridge health endpoint is unreachable",
            exception,
        )
    }

    override suspend fun submitCreate(query: String): CreateResponse {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            throw SimulatorException(SimulatorErrorCode.QUERY_EMPTY, "Query must not be blank")
        }
        if (query.length > config.maxQueryChars) {
            throw SimulatorException(
                SimulatorErrorCode.QUERY_TOO_LARGE,
                "Query exceeds ${config.maxQueryChars} characters",
            )
        }

        val request = CreateRequest(
            protocolVersion = config.expectedProtocolVersion,
            requestId = UUID.randomUUID().toString(),
            query = query,
        )

        val response = try {
            client.post("$baseUrl/v1/create") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<CreateResponse>()
        } catch (exception: Exception) {
            throw SimulatorException(
                SimulatorErrorCode.BRIDGE_UNREACHABLE,
                "Bridge create endpoint is unreachable",
                exception,
            )
        }

        if (response.requestId != request.requestId) {
            throw SimulatorException(
                SimulatorErrorCode.RESPONSE_REQUEST_ID_MISMATCH,
                "Bridge response requestId does not match request",
            )
        }
        return response
    }

    override fun close() {
        client.close()
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
        }
    }
}
