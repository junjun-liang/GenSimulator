package com.flyme.auto.gensimulator.bridge

import com.flyme.auto.gensimulator.protocol.BridgeHealth
import com.flyme.auto.gensimulator.protocol.CreateResponse

interface BridgeClient : AutoCloseable {
    suspend fun health(): BridgeHealth
    suspend fun submitCreate(query: String): CreateResponse
}
