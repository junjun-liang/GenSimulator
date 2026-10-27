package com.flyme.auto.gensimulator.presentation

import com.flyme.auto.gensimulator.adb.DeviceInfo
import com.flyme.auto.gensimulator.history.RequestRecord
import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode

data class SimulatorUiState(
    val adbReady: Boolean = false,
    val adbPath: String? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val selectedDevice: DeviceInfo? = null,
    val genCardsInstalled: Boolean = false,
    val bridgeInstalled: Boolean = false,
    val bridgeReachable: Boolean = false,
    val binderConnected: Boolean = false,
    val contractMatched: Boolean = false,
    val contractVersion: Int? = null,
    val protocolVersion: Int? = null,
    val signatureMatched: Boolean? = null,
    val binderState: String? = null,
    val query: String = "",
    val queryLength: Int = 0,
    val sending: Boolean = false,
    val lastRequestId: String? = null,
    val lastResultCode: SimulatorErrorCode? = null,
    val lastWireCode: String? = null,
    val lastMessage: String? = null,
    val lastLatencyMs: Long? = null,
    val history: List<RequestRecord> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val bridgeConfigured: Boolean = true,
)
