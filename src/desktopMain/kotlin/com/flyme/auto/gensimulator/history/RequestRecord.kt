package com.flyme.auto.gensimulator.history

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class RequestRecord(
    val requestId: String,
    val deviceSerial: String,
    @Serializable(with = InstantAsStringSerializer::class)
    val createdAt: Instant,
    val queryPreview: String,
    val queryLength: Int,
    val resultCode: String,
    val latencyMs: Long,
)
