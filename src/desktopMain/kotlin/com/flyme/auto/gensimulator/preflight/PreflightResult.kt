package com.flyme.auto.gensimulator.preflight

import com.flyme.auto.gensimulator.protocol.BridgeHealth
import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode

sealed interface PreflightResult {
    data class Success(
        val health: BridgeHealth,
        val userId: Int,
    ) : PreflightResult
    data class Failure(
        val code: SimulatorErrorCode,
        val message: String,
    ) : PreflightResult
}
