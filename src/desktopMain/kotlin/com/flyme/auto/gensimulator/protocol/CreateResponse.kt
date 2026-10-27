package com.flyme.auto.gensimulator.protocol

import kotlinx.serialization.Serializable

@Serializable
data class CreateResponse(
    val requestId: String,
    val accepted: Boolean,
    val code: String,
)
