package com.flyme.auto.gensimulator.presentation

import com.flyme.auto.gensimulator.adb.AdbClient
import com.flyme.auto.gensimulator.adb.AdbCommandResult
import com.flyme.auto.gensimulator.adb.AdbLocator
import com.flyme.auto.gensimulator.adb.DeviceInfo
import com.flyme.auto.gensimulator.adb.DeviceState
import com.flyme.auto.gensimulator.bridge.BridgeClient
import com.flyme.auto.gensimulator.config.SimulatorConfig
import com.flyme.auto.gensimulator.diagnostics.SimulatorLogger
import com.flyme.auto.gensimulator.document.TextDocumentLoader
import com.flyme.auto.gensimulator.history.HistoryStore
import com.flyme.auto.gensimulator.history.RequestRecord
import com.flyme.auto.gensimulator.platform.PlatformEnvironment
import com.flyme.auto.gensimulator.preflight.DevicePreflight
import com.flyme.auto.gensimulator.protocol.BridgeHealth
import com.flyme.auto.gensimulator.protocol.CreateResponse
import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulatorViewModelTest {
    private val device = DeviceInfo("serial-123", DeviceState.DEVICE, "ZEEKR", null, null)

    @Test
    fun emptyQueryIsRejectedLocally() = runTest {
        val harness = harness(this)
        harness.viewModel.sendNow()
        assertEquals(SimulatorErrorCode.QUERY_EMPTY, harness.viewModel.state.value.lastResultCode)
        assertTrue(harness.history.records.isEmpty())
        harness.viewModel.close()
    }

    @Test
    fun acceptedResponseAddsHistory() = runTest {
        val harness = harness(this)
        harness.viewModel.updateQuery("帮我生成座椅加热卡")
        harness.viewModel.sendNow()

        val state = harness.viewModel.state.value
        assertEquals(SimulatorErrorCode.INGRESS_ACCEPTED, state.lastResultCode)
        assertEquals("INGRESS_ACCEPTED", state.lastWireCode)
        assertEquals(1, harness.history.records.size)
        assertTrue(harness.adb.startedActivities.isEmpty())
        assertFalse(state.sending)
        harness.viewModel.close()
    }

    @Test
    fun binderFailurePreservesStableWireCode() = runTest {
        val harness = harness(
            scope = this,
            bridge = TestBridgeClient(
                CreateResponse("request-1", false, "GENCARDS_PERMISSION_DENIED")
            ),
        )
        harness.viewModel.updateQuery("query")
        harness.viewModel.sendNow()

        assertEquals(
            SimulatorErrorCode.GENCARDS_PERMISSION_DENIED,
            harness.viewModel.state.value.lastResultCode,
        )
        assertEquals(
            "GENCARDS_PERMISSION_DENIED",
            harness.viewModel.state.value.lastWireCode,
        )
        harness.viewModel.close()
    }

    @Test
    fun legacyForegroundEntryCodeFallsBackToRejectedAndKeepsWireCode() = runTest {
        val harness = harness(
            scope = this,
            bridge = TestBridgeClient(
                CreateResponse("request-1", false, "GENCARDS_ENTRY_PERMISSION_DENIED")
            ),
        )
        harness.viewModel.updateQuery("query")
        harness.viewModel.sendNow()

        assertEquals(SimulatorErrorCode.INGRESS_REJECTED, harness.viewModel.state.value.lastResultCode)
        assertEquals(
            "GENCARDS_ENTRY_PERMISSION_DENIED",
            harness.viewModel.state.value.lastWireCode,
        )
        harness.viewModel.close()
    }

    @Test
    fun rejectedResponseIsNotReportedAsAccepted() = runTest {
        val harness = harness(
            scope = this,
            bridge = TestBridgeClient(CreateResponse("request-1", false, "INGRESS_REJECTED")),
        )
        harness.viewModel.updateQuery("query")
        harness.viewModel.sendNow()

        assertEquals(SimulatorErrorCode.INGRESS_REJECTED, harness.viewModel.state.value.lastResultCode)
        assertEquals(1, harness.history.records.size)
        harness.viewModel.close()
    }

    private suspend fun harness(
        scope: TestScope,
        bridge: TestBridgeClient = TestBridgeClient(CreateResponse("request-1", true, "INGRESS_ACCEPTED")),
    ): Harness {
        val config = SimulatorConfig(
            genCardsPackage = "gencards",
            bridgePackage = "bridge",
            expectedContractVersion = 2,
        )
        val adb = TestAdbClient(device)
        val history = InMemoryHistoryStore()
        val temp = createTempDirectory("simulator-viewmodel")
        val logger = SimulatorLogger(temp.resolve("logs").resolve("test.log"))
        val viewModel = SimulatorViewModel(
            config = config,
            adbLocator = AdbLocator { Path.of("adb") },
            adbClient = adb,
            bridgeClient = bridge,
            preflight = DevicePreflight(adb, bridge, config),
            documentLoader = TextDocumentLoader(),
            historyStore = history,
            logger = logger,
            platformEnvironment = TestPlatformEnvironment,
            scope = TestScope(UnconfinedTestDispatcher(scope.testScheduler)),
        )
        viewModel.refreshDevicesNow()
        return Harness(viewModel, history, adb)
    }

    private data class Harness(
        val viewModel: SimulatorViewModel,
        val history: InMemoryHistoryStore,
        val adb: TestAdbClient,
    )
}

private class TestAdbClient(private val device: DeviceInfo) : AdbClient {
    val startedActivities = mutableListOf<String>()
    override suspend fun version() = AdbCommandResult(0, "Android Debug Bridge", "")
    override suspend fun listDevices() = listOf(device)
    override suspend fun currentUserId(serial: String) = 10
    override suspend fun isPackageInstalled(serial: String, userId: Int, packageName: String) = true
    override suspend fun forward(serial: String, localPort: Int, remotePort: Int) = AdbCommandResult(0, "", "")
    override suspend fun removeForward(serial: String, localPort: Int) = AdbCommandResult(0, "", "")
    override suspend fun startActivity(serial: String, userId: Int, component: String): AdbCommandResult {
        startedActivities += component
        return AdbCommandResult(0, "", "")
    }
}

private class TestBridgeClient(private val template: CreateResponse) : BridgeClient {
    override suspend fun health() = BridgeHealth("ready", true, true, 2, 1)
    override suspend fun submitCreate(query: String): CreateResponse = template
    override fun close() = Unit
}

private class InMemoryHistoryStore : HistoryStore {
    val records = mutableListOf<RequestRecord>()
    override suspend fun load(): List<RequestRecord> = records.toList()
    override suspend fun append(record: RequestRecord) { records.add(0, record) }
    override suspend fun clear() { records.clear() }
}

private object TestPlatformEnvironment : PlatformEnvironment {
    override suspend fun selectTextFile(): Path? = null
    override fun openDirectory(path: Path) = Unit
}
