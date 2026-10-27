package com.flyme.auto.gensimulator.preflight

import com.flyme.auto.gensimulator.adb.AdbClient
import com.flyme.auto.gensimulator.adb.AdbCommandResult
import com.flyme.auto.gensimulator.adb.DeviceInfo
import com.flyme.auto.gensimulator.adb.DeviceState
import com.flyme.auto.gensimulator.bridge.BridgeClient
import com.flyme.auto.gensimulator.config.SimulatorConfig
import com.flyme.auto.gensimulator.protocol.BridgeHealth
import com.flyme.auto.gensimulator.protocol.CreateResponse
import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode
import com.flyme.auto.gensimulator.protocol.SimulatorException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PreflightTest {
    private val device = DeviceInfo("serial", DeviceState.DEVICE, "model", null, null)
    private val config = SimulatorConfig(
        genCardsPackage = "gencards",
        bridgePackage = "bridge",
        bridgeBootstrapActivityComponent = "bridge/.BridgeBootstrapActivity",
        expectedContractVersion = 2,
    )

    @Test
    fun missingDeviceFails() = runTest {
        val result = preflight(FakeAdbClient(), FakeBridgeClient()).run(null)
        assertEquals(SimulatorErrorCode.DEVICE_NOT_CONNECTED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun missingGenCardsFails() = runTest {
        val result = preflight(
            FakeAdbClient(installedPackages = setOf("bridge")),
            FakeBridgeClient(),
        ).run(device)
        assertEquals(SimulatorErrorCode.GENCARDS_NOT_INSTALLED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun missingBridgeFails() = runTest {
        val result = preflight(
            FakeAdbClient(installedPackages = setOf("gencards")),
            FakeBridgeClient(),
        ).run(device)
        assertEquals(SimulatorErrorCode.BRIDGE_NOT_INSTALLED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun unauthorizedDeviceFails() = runTest {
        val unauthorized = device.copy(state = DeviceState.UNAUTHORIZED)
        val result = preflight(FakeAdbClient(), FakeBridgeClient()).run(unauthorized)
        assertEquals(SimulatorErrorCode.DEVICE_UNAUTHORIZED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun unresolvedActiveUserIsAnAdbFailureNotAnInstallationFailure() = runTest {
        val result = preflight(FakeAdbClient(currentUserId = null), FakeBridgeClient()).run(device)
        assertEquals(SimulatorErrorCode.ADB_EXEC_FAILED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun bridgeStartFailureIsExplicit() = runTest {
        val result = preflight(
            FakeAdbClient(startActivityResult = AdbCommandResult(1, "", "not allowed")),
            SequencedBridgeClient(1, BridgeHealth("ready", true, true, 2, 1)),
        ).run(device)
        assertEquals(SimulatorErrorCode.BRIDGE_START_FAILED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun binderFalseFails() = runTest {
        val result = preflight(
            FakeAdbClient(),
            FakeBridgeClient(health = BridgeHealth("ready", true, false, 2, 1, true, "BIND_FAILED")),
        ).run(device)
        assertEquals(SimulatorErrorCode.GENCARDS_BIND_FAILED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun signatureMismatchFailsAsPermissionDenied() = runTest {
        val result = preflight(
            FakeAdbClient(),
            FakeBridgeClient(health = BridgeHealth("ready", true, false, 2, 1, false, "PERMISSION_DENIED")),
        ).run(device)
        assertEquals(SimulatorErrorCode.GENCARDS_PERMISSION_DENIED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun binderDeathIsPreserved() = runTest {
        val result = preflight(
            FakeAdbClient(),
            FakeBridgeClient(
                health = BridgeHealth(
                    "ready",
                    true,
                    false,
                    2,
                    1,
                    true,
                    "BINDER_DIED",
                    "GENCARDS_BINDER_DIED",
                )
            ),
        ).run(device)
        assertEquals(SimulatorErrorCode.GENCARDS_BINDER_DIED, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun contractMismatchFails() = runTest {
        val result = preflight(
            FakeAdbClient(),
            FakeBridgeClient(health = BridgeHealth("ready", true, true, 9, 1)),
        ).run(device)
        assertEquals(SimulatorErrorCode.CONTRACT_VERSION_MISMATCH, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun protocolMismatchFails() = runTest {
        val result = preflight(
            FakeAdbClient(),
            FakeBridgeClient(health = BridgeHealth("ready", true, true, 2, 99)),
        ).run(device)
        assertEquals(SimulatorErrorCode.PROTOCOL_VERSION_MISMATCH, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun healthRetriesStartupRace() = runTest {
        val bridge = SequencedBridgeClient(
            failuresBeforeSuccess = 2,
            health = BridgeHealth("ready", true, true, 2, 1, true, "CONNECTED"),
        )
        val result = preflight(FakeAdbClient(), bridge, attempts = 3).run(device)
        assertIs<PreflightResult.Success>(result)
        assertEquals(3, bridge.healthCalls)
    }

    @Test
    fun binderConnectingHealthIsRetriedUntilConnected() = runTest {
        val bridge = HealthSequenceBridgeClient(
            listOf(
                BridgeHealth("ready", true, false, 2, 1, true, "CONNECTING"),
                BridgeHealth("ready", true, true, 2, 1, true, "CONNECTED"),
            )
        )
        val result = preflight(FakeAdbClient(), bridge, attempts = 2).run(device)
        assertIs<PreflightResult.Success>(result)
        assertEquals(2, bridge.healthCalls)
    }

    @Test
    fun healthRetryExhaustionIsExplicit() = runTest {
        val bridge = SequencedBridgeClient(
            failuresBeforeSuccess = Int.MAX_VALUE,
            health = BridgeHealth("ready", true, true, 2, 1),
        )
        val result = preflight(FakeAdbClient(), bridge, attempts = 2).run(device)
        assertEquals(SimulatorErrorCode.BRIDGE_HEALTH_TIMEOUT, assertIs<PreflightResult.Failure>(result).code)
    }

    @Test
    fun healthyBridgeSkipsVisibleBootstrapActivity() = runTest {
        val adb = FakeAdbClient()
        val result = preflight(adb, FakeBridgeClient()).run(device)
        assertIs<PreflightResult.Success>(result)
        assertTrue(adb.startedActivities.isEmpty())
        assertEquals(listOf(10 to "gencards", 10 to "bridge"), adb.checkedPackages)
        assertTrue(adb.forwardCalled)
    }

    @Test
    fun unreachableBridgeStartsBootstrapActivity() = runTest {
        val adb = FakeAdbClient()
        val bridge = SequencedBridgeClient(1, BridgeHealth("ready", true, true, 2, 1))
        val result = preflight(adb, bridge).run(device)
        assertIs<PreflightResult.Success>(result)
        assertEquals(listOf(10 to "bridge/.BridgeBootstrapActivity"), adb.startedActivities)
        assertEquals(2, bridge.healthCalls)
    }

    private fun preflight(
        adb: AdbClient,
        bridge: BridgeClient,
        attempts: Int = 1,
    ) = DevicePreflight(adb, bridge, config, healthAttempts = attempts, healthRetryDelayMs = 0)
}

internal class FakeAdbClient(
    private val installedPackages: Set<String> = setOf("gencards", "bridge"),
    private val startActivityResult: AdbCommandResult = AdbCommandResult(0, "Starting activity", ""),
    private val currentUserId: Int? = 10,
) : AdbClient {
    val checkedPackages = mutableListOf<Pair<Int, String>>()
    val startedActivities = mutableListOf<Pair<Int, String>>()
    var forwardCalled = false

    override suspend fun version() = AdbCommandResult(0, "Android Debug Bridge", "")
    override suspend fun listDevices() = emptyList<DeviceInfo>()
    override suspend fun currentUserId(serial: String) = currentUserId
    override suspend fun isPackageInstalled(serial: String, userId: Int, packageName: String): Boolean {
        checkedPackages += userId to packageName
        return packageName in installedPackages
    }
    override suspend fun forward(serial: String, localPort: Int, remotePort: Int): AdbCommandResult {
        forwardCalled = true
        return AdbCommandResult(0, "", "")
    }
    override suspend fun removeForward(serial: String, localPort: Int) = AdbCommandResult(0, "", "")
    override suspend fun startActivity(serial: String, userId: Int, component: String): AdbCommandResult {
        startedActivities += userId to component
        return startActivityResult
    }
}

internal class FakeBridgeClient(
    private val health: BridgeHealth = BridgeHealth("ready", true, true, 2, 1, true, "CONNECTED"),
    private val createResponse: CreateResponse = CreateResponse("unused", true, "INGRESS_ACCEPTED"),
) : BridgeClient {
    override suspend fun health() = health
    override suspend fun submitCreate(query: String) = createResponse
    override fun close() = Unit
}

private class SequencedBridgeClient(
    private val failuresBeforeSuccess: Int,
    private val health: BridgeHealth,
) : BridgeClient {
    var healthCalls = 0

    override suspend fun health(): BridgeHealth {
        healthCalls++
        if (healthCalls <= failuresBeforeSuccess) {
            throw SimulatorException(SimulatorErrorCode.BRIDGE_UNREACHABLE, "not ready")
        }
        return health
    }

    override suspend fun submitCreate(query: String) = CreateResponse("unused", true, "INGRESS_ACCEPTED")
    override fun close() = Unit
}

private class HealthSequenceBridgeClient(
    private val healthSequence: List<BridgeHealth>,
) : BridgeClient {
    var healthCalls = 0

    override suspend fun health(): BridgeHealth {
        val index = healthCalls.coerceAtMost(healthSequence.lastIndex)
        healthCalls++
        return healthSequence[index]
    }

    override suspend fun submitCreate(query: String) = CreateResponse("unused", true, "INGRESS_ACCEPTED")
    override fun close() = Unit
}
