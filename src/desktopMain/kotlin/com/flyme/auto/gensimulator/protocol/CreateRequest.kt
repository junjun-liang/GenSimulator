package com.flyme.auto.gensimulator.protocol

import kotlinx.serialization.Serializable

@Serializable
data class CreateRequest(
    val protocolVersion: Int,
    val requestId: String,
    val query: String,
)
