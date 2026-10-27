package com.flyme.auto.gensimulator.preflight

import com.flyme.auto.gensimulator.adb.AdbClient
import com.flyme.auto.gensimulator.adb.DeviceInfo
import com.flyme.auto.gensimulator.adb.DeviceState
import com.flyme.auto.gensimulator.bridge.BridgeClient
import com.flyme.auto.gensimulator.config.SimulatorConfig
import com.flyme.auto.gensimulator.protocol.BridgeHealth
import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode
import com.flyme.auto.gensimulator.protocol.SimulatorException
import kotlinx.coroutines.delay

class DevicePreflight(
    private val adbClient: AdbClient,
    private val bridgeClient: BridgeClient,
    private val config: SimulatorConfig,
    private val healthAttempts: Int = 10,
    private val healthRetryDelayMs: Long = 250L,
) {
    suspend fun run(device: DeviceInfo?): PreflightResult {
        if (!config.isBridgeConfigured) {
            return failure(SimulatorErrorCode.CONFIG_INVALID, "Bridge package is not configured")
        }
        if (device == null) {
            return failure(SimulatorErrorCode.DEVICE_NOT_CONNECTED, "No device is selected")
        }
        when (device.state) {
            DeviceState.OFFLINE -> return failure(SimulatorErrorCode.DEVICE_OFFLINE, "Device is offline")
            DeviceState.UNAUTHORIZED -> return failure(SimulatorErrorCode.DEVICE_UNAUTHORIZED, "Device is unauthorized")
            DeviceState.UNKNOWN -> return failure(SimulatorErrorCode.DEVICE_NOT_CONNECTED, "Device state is unknown")
            DeviceState.DEVICE -> Unit
        }

        return try {
            adbClient.version().also {
                if (!it.isSuccess) return failure(SimulatorErrorCode.ADB_EXEC_FAILED, "adb version failed")
            }
            val userId = adbClient.currentUserId(device.serial)
                ?: return failure(SimulatorErrorCode.ADB_EXEC_FAILED, "Unable to determine the active Android user")

            if (!adbClient.isPackageInstalled(device.serial, userId, config.genCardsPackage)) {
                return failure(SimulatorErrorCode.GENCARDS_NOT_INSTALLED, "GenCards is not installed")
            }
            if (!adbClient.isPackageInstalled(device.serial, userId, config.bridgePackage)) {
                return failure(SimulatorErrorCode.BRIDGE_NOT_INSTALLED, "GenSimulator Android Bridge is not installed")
            }

            val forward = adbClient.forward(device.serial, config.bridgePort, config.bridgeDevicePort)
            if (!forward.isSuccess) {
                return failure(SimulatorErrorCode.ADB_EXEC_FAILED, "adb forward failed")
            }

            val existingHealth = try {
                bridgeClient.health()
            } catch (exception: SimulatorException) {
                if (exception.code != SimulatorErrorCode.BRIDGE_UNREACHABLE) throw exception
                null
            }
            if (existingHealth == null) {
                val start = adbClient.startActivity(
                    device.serial,
                    userId,
                    config.bridgeBootstrapActivityComponent,
                )
                val startOutput = (start.stdout + "\n" + start.stderr).trim()
                if (!start.isSuccess || startOutput.lineSequence().any { it.trim().startsWith("Error:", ignoreCase = true) }) {
                    return failure(
                        SimulatorErrorCode.BRIDGE_START_FAILED,
                        "Failed to start GenSimulator Bridge bootstrap activity: $startOutput",
                    )
                }
            }

            val health = (existingHealth?.takeUnless(::shouldRetryHealth) ?: awaitHealth())
                ?: return failure(
                    SimulatorErrorCode.BRIDGE_HEALTH_TIMEOUT,
                    "Bridge did not become ready after $healthAttempts health attempts",
                )

            validateHealth(health)?.let { return it }
            PreflightResult.Success(health, userId)
        } catch (exception: SimulatorException) {
            failure(exception.code, exception.message ?: exception.code.name)
        } catch (exception: SecurityException) {
            failure(SimulatorErrorCode.GENCARDS_PERMISSION_DENIED, exception.message ?: "Permission denied")
        } catch (exception: Exception) {
            failure(SimulatorErrorCode.UNKNOWN_ERROR, exception.message ?: "Unknown preflight failure")
        }
    }

    private suspend fun awaitHealth(): BridgeHealth? {
        val attempts = healthAttempts.coerceAtLeast(1)
        var lastHealth: BridgeHealth? = null
        repeat(attempts) { attempt ->
            try {
                val health = bridgeClient.health()
                lastHealth = health
                if (!shouldRetryHealth(health)) return health
            } catch (exception: SimulatorException) {
                if (exception.code != SimulatorErrorCode.BRIDGE_UNREACHABLE) throw exception
            }
            if (attempt + 1 < attempts && healthRetryDelayMs > 0) {
                delay(healthRetryDelayMs)
            }
        }
        return lastHealth
    }

    private fun shouldRetryHealth(health: BridgeHealth): Boolean {
        if (health.bridge.lowercase() != "ready") return true
        if (health.protocolVersion != null && health.protocolVersion != config.expectedProtocolVersion) return false
        if (config.expectedContractVersion != null && health.contractVersion != config.expectedContractVersion) return false
        if (!health.genCardsInstalled || health.sameSigner == false) return false
        if (health.lastErrorCode in setOf(
                SimulatorErrorCode.GENCARDS_PERMISSION_DENIED.name,
                SimulatorErrorCode.GENCARDS_NOT_INSTALLED.name,
            )
        ) return false
        return !health.binderConnected
    }

    private fun validateHealth(health: BridgeHealth): PreflightResult.Failure? {
        if (health.bridge.lowercase() != "ready") {
            return failure(SimulatorErrorCode.BRIDGE_UNREACHABLE, "Bridge is not ready")
        }
        if (health.protocolVersion != null && health.protocolVersion != config.expectedProtocolVersion) {
            return failure(
                SimulatorErrorCode.PROTOCOL_VERSION_MISMATCH,
                "Protocol version ${health.protocolVersion} != expected ${config.expectedProtocolVersion}",
            )
        }
        if (config.expectedContractVersion != null && health.contractVersion != config.expectedContractVersion) {
            return failure(
                SimulatorErrorCode.CONTRACT_VERSION_MISMATCH,
                "Contract version ${health.contractVersion} != expected ${config.expectedContractVersion}",
            )
        }
        if (!health.genCardsInstalled) {
            return failure(SimulatorErrorCode.GENCARDS_NOT_INSTALLED, "Bridge reports GenCards missing")
        }
        if (health.sameSigner == false || health.lastErrorCode == SimulatorErrorCode.GENCARDS_PERMISSION_DENIED.name) {
            return failure(
                SimulatorErrorCode.GENCARDS_PERMISSION_DENIED,
                "Bridge and GenCards are not in the same trusted signing domain",
            )
        }
        if (!health.binderConnected) {
            val code = when (health.lastErrorCode) {
                SimulatorErrorCode.GENCARDS_BINDER_DIED.name -> SimulatorErrorCode.GENCARDS_BINDER_DIED
                SimulatorErrorCode.GENCARDS_PERMISSION_DENIED.name -> SimulatorErrorCode.GENCARDS_PERMISSION_DENIED
                SimulatorErrorCode.GENCARDS_NOT_INSTALLED.name -> SimulatorErrorCode.GENCARDS_NOT_INSTALLED
                else -> SimulatorErrorCode.GENCARDS_BIND_FAILED
            }
            return failure(code, "Bridge is not connected to GenCards Binder (${health.binderState ?: "unknown"})")
        }
        return null
    }

    private fun failure(code: SimulatorErrorCode, message: String) = PreflightResult.Failure(code, message)
}
