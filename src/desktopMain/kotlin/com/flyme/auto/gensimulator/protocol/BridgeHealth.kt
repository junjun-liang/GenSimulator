package com.flyme.auto.gensimulator.protocol

import kotlinx.serialization.Serializable

@Serializable
data class BridgeHealth(
    val bridge: String,
    val genCardsInstalled: Boolean,
    val binderConnected: Boolean,
    val contractVersion: Int,
    val protocolVersion: Int? = null,
    val sameSigner: Boolean? = null,
    val binderState: String? = null,
    val lastErrorCode: String? = null,
)
