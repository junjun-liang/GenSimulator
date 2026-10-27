package com.flyme.auto.gensimulator.presentation

import com.flyme.auto.gensimulator.adb.AdbClient
import com.flyme.auto.gensimulator.adb.AdbLocator
import com.flyme.auto.gensimulator.adb.DeviceInfo
import com.flyme.auto.gensimulator.bridge.BridgeClient
import com.flyme.auto.gensimulator.config.SimulatorConfig
import com.flyme.auto.gensimulator.diagnostics.SimulatorLogger
import com.flyme.auto.gensimulator.document.TextDocumentLoader
import com.flyme.auto.gensimulator.history.HistoryStore
import com.flyme.auto.gensimulator.history.RequestRecord
import com.flyme.auto.gensimulator.platform.PlatformEnvironment
import com.flyme.auto.gensimulator.preflight.DevicePreflight
import com.flyme.auto.gensimulator.preflight.PreflightResult
import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode
import com.flyme.auto.gensimulator.protocol.SimulatorException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.max

class SimulatorViewModel(
    private val config: SimulatorConfig,
    private val adbLocator: AdbLocator,
    private val adbClient: AdbClient,
    private val bridgeClient: BridgeClient,
    private val preflight: DevicePreflight,
    private val documentLoader: TextDocumentLoader,
    private val historyStore: HistoryStore,
    private val logger: SimulatorLogger,
    private val platformEnvironment: PlatformEnvironment,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private val _state = MutableStateFlow(
        SimulatorUiState(
            bridgeConfigured = config.isBridgeConfigured,
        )
    )
    val state: StateFlow<SimulatorUiState> = _state.asStateFlow()

    init {
        scope.launch {
            _state.value = _state.value.copy(history = historyStore.load())
            refreshDevicesNow()
        }
    }

    fun refreshDevices() {
        scope.launch { refreshDevicesNow() }
    }

    suspend fun refreshDevicesNow() {
        try {
            val adbPath = adbLocator.locate()
            if (adbPath == null) {
                updateError(SimulatorErrorCode.ADB_NOT_FOUND, "ADB was not found")
                _state.value = _state.value.copy(adbReady = false, adbPath = null, devices = emptyList())
                return
            }
            val version = adbClient.version()
            if (!version.isSuccess) throw SimulatorException(SimulatorErrorCode.ADB_EXEC_FAILED, "adb version failed")

            val devices = adbClient.listDevices()
            val currentSerial = _state.value.selectedDevice?.serial
            val selected = when {
                devices.size == 1 -> devices.single()
                currentSerial != null -> devices.firstOrNull { it.serial == currentSerial }
                else -> null
            }

            _state.value = _state.value.copy(
                adbReady = true,
                adbPath = adbPath.toString(),
                devices = devices,
                selectedDevice = selected,
                lastResultCode = if (devices.size > 1 && selected == null) SimulatorErrorCode.MULTIPLE_DEVICES_NOT_SELECTED else _state.value.lastResultCode,
            )
            log("DEVICE", "discovered=${devices.size} selected=${selected?.serial?.let(::maskSerial) ?: "none"}")
            if (selected != null) refreshConnectionStatusNow()
        } catch (exception: SimulatorException) {
            updateError(exception.code, exception.message ?: exception.code.name)
        } catch (exception: Exception) {
            updateError(SimulatorErrorCode.ADB_EXEC_FAILED, exception.message ?: "ADB failure")
        }
    }

    fun selectDevice(serial: String) {
        val previous = _state.value.selectedDevice
        val device = _state.value.devices.firstOrNull { it.serial == serial } ?: return
        _state.value = _state.value.copy(
            selectedDevice = device,
            genCardsInstalled = false,
            bridgeInstalled = false,
            bridgeReachable = false,
            binderConnected = false,
            contractMatched = false,
            contractVersion = null,
            protocolVersion = null,
            signatureMatched = null,
            binderState = null,
        )
        scope.launch {
            if (previous != null && previous.serial != serial) {
                runCatching { adbClient.removeForward(previous.serial, config.bridgePort) }
                log("ADB", "removed old forward device=${maskSerial(previous.serial)} port=${config.bridgePort}")
            }
            log("DEVICE", "selected ${maskSerial(serial)}")
            refreshConnectionStatusNow()
        }
    }

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query, queryLength = query.length)
    }

    fun selectTextFile() {
        scope.launch {
            val path = platformEnvironment.selectTextFile() ?: return@launch
            try {
                val text = documentLoader.load(path)
                updateQuery(text)
                log("REQUEST", "loaded_file=${path.fileName} length=${text.length}")
            } catch (exception: SimulatorException) {
                updateError(exception.code, exception.message ?: "Failed to read file")
            }
        }
    }

    fun send() {
        scope.launch { sendNow() }
    }

    suspend fun sendNow() {
        val snapshot = _state.value
        val query = snapshot.query
        if (query.isBlank()) {
            updateError(SimulatorErrorCode.QUERY_EMPTY, "Query must not be blank")
            return
        }
        if (query.length > config.maxQueryChars) {
            updateError(SimulatorErrorCode.QUERY_TOO_LARGE, "Query exceeds ${config.maxQueryChars} characters")
            return
        }

        val device = snapshot.selectedDevice
        if (device == null) {
            updateError(
                if (snapshot.devices.size > 1) SimulatorErrorCode.MULTIPLE_DEVICES_NOT_SELECTED
                else SimulatorErrorCode.DEVICE_NOT_CONNECTED,
                "Select a connected device before sending",
            )
            return
        }

        val startNanos = System.nanoTime()
        _state.value = _state.value.copy(sending = true, lastMessage = null)
        log("REQUEST", "start device=${maskSerial(device.serial)} length=${query.length}")

        try {
            when (val result = preflight.run(device)) {
                is PreflightResult.Failure -> {
                    applyPreflightFailure(result)
                    return
                }
                is PreflightResult.Success -> {
                    applyHealth(result)
                }
            }

            val response = bridgeClient.submitCreate(query)
            val latency = elapsedMs(startNanos)
            val code = if (response.accepted) {
                SimulatorErrorCode.INGRESS_ACCEPTED
            } else {
                val wire = SimulatorErrorCode.fromWireCode(response.code)
                if (wire == SimulatorErrorCode.UNKNOWN_ERROR) SimulatorErrorCode.INGRESS_REJECTED else wire
            }
            val message = when {
                response.accepted -> "GenCards ingress accepted"
                // GenCards admits a single pending CREATE or active (possibly backgrounded) Generation session.
                response.code == SimulatorErrorCode.INGRESS_REJECTED.name ->
                    "GenCards 未接收：可能已有进行中/后台的生成会话或待处理请求"
                else -> "GenCards ingress rejected"
            }

            _state.value = _state.value.copy(
                lastRequestId = response.requestId,
                lastResultCode = code,
                lastWireCode = response.code,
                lastMessage = message,
                lastLatencyMs = latency,
            )

            appendHistory(device, response.requestId, query, response.code, latency)
            log("RESPONSE", "code=${response.code} latency=${latency}ms")
        } catch (exception: SimulatorException) {
            updateError(exception.code, exception.message ?: exception.code.name, elapsedMs(startNanos))
        } catch (exception: Exception) {
            updateError(SimulatorErrorCode.UNKNOWN_ERROR, exception.message ?: "Unknown send failure", elapsedMs(startNanos))
        } finally {
            _state.value = _state.value.copy(sending = false)
        }
    }

    fun clearHistory() {
        scope.launch {
            historyStore.clear()
            _state.value = _state.value.copy(history = emptyList())
        }
    }

    fun openLogsDirectory() {
        platformEnvironment.openDirectory(logger.logDirectory())
    }

    private suspend fun refreshConnectionStatusNow() {
        when (val result = preflight.run(_state.value.selectedDevice)) {
            is PreflightResult.Success -> applyHealth(result)
            is PreflightResult.Failure -> applyPreflightFailure(result)
        }
    }

    private fun applyHealth(result: PreflightResult.Success) {
        val health = result.health
        _state.value = _state.value.copy(
            genCardsInstalled = health.genCardsInstalled,
            bridgeInstalled = true,
            bridgeReachable = health.bridge.lowercase() == "ready",
            binderConnected = health.binderConnected,
            contractMatched = config.expectedContractVersion == null || config.expectedContractVersion == health.contractVersion,
            contractVersion = health.contractVersion,
            protocolVersion = health.protocolVersion,
            signatureMatched = health.sameSigner,
            binderState = health.binderState,
        )
    }

    private suspend fun applyPreflightFailure(result: PreflightResult.Failure) {
        val current = _state.value
        _state.value = current.copy(
            genCardsInstalled = result.code != SimulatorErrorCode.GENCARDS_NOT_INSTALLED && current.genCardsInstalled,
            bridgeInstalled = result.code != SimulatorErrorCode.BRIDGE_NOT_INSTALLED && current.bridgeInstalled,
            bridgeReachable = result.code !in setOf(
                SimulatorErrorCode.BRIDGE_UNREACHABLE,
                SimulatorErrorCode.BRIDGE_HEALTH_TIMEOUT,
                SimulatorErrorCode.BRIDGE_START_FAILED,
                SimulatorErrorCode.BRIDGE_NOT_INSTALLED,
            ) && current.bridgeReachable,
            binderConnected = result.code !in setOf(SimulatorErrorCode.GENCARDS_BIND_FAILED, SimulatorErrorCode.GENCARDS_BINDER_DIED) && current.binderConnected,
        )
        updateError(result.code, result.message)
    }

    private suspend fun appendHistory(
        device: DeviceInfo,
        requestId: String,
        query: String,
        resultCode: String,
        latencyMs: Long,
    ) {
        val record = RequestRecord(
            requestId = requestId,
            deviceSerial = device.serial,
            createdAt = Instant.now(),
            queryPreview = query.replace(Regex("\\s+"), " ").take(80),
            queryLength = query.length,
            resultCode = resultCode,
            latencyMs = latencyMs,
        )
        historyStore.append(record)
        _state.value = _state.value.copy(history = (listOf(record) + _state.value.history).take(200))
    }

    private suspend fun updateError(code: SimulatorErrorCode, message: String, latencyMs: Long? = null) {
        _state.value = _state.value.copy(
            lastResultCode = code,
            lastWireCode = null,
            lastMessage = message,
            lastLatencyMs = latencyMs,
        )
        log("ERROR", "code=${code.name} message=${message.take(160)}")
    }

    private suspend fun log(category: String, message: String) {
        logger.log(category, message)
        _state.value = _state.value.copy(diagnostics = logger.snapshot())
    }

    private fun elapsedMs(startNanos: Long): Long = max(0L, (System.nanoTime() - startNanos) / 1_000_000)

    private fun maskSerial(serial: String): String = when {
        serial.length <= 4 -> "****"
        else -> serial.take(2) + "***" + serial.takeLast(2)
    }

    override fun close() {
        scope.cancel()
        bridgeClient.close()
    }
}
