package com.flyme.auto.gensimulator.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.flyme.auto.gensimulator.adb.DefaultAdbLocator
import com.flyme.auto.gensimulator.adb.ProcessAdbClient
import com.flyme.auto.gensimulator.bridge.HttpBridgeClient
import com.flyme.auto.gensimulator.config.SimulatorConfig
import com.flyme.auto.gensimulator.diagnostics.SimulatorLogger
import com.flyme.auto.gensimulator.document.TextDocumentLoader
import com.flyme.auto.gensimulator.history.JsonFileHistoryStore
import com.flyme.auto.gensimulator.platform.currentPlatformEnvironment
import com.flyme.auto.gensimulator.preflight.DevicePreflight
import com.flyme.auto.gensimulator.presentation.SimulatorViewModel
import java.nio.file.Path

fun main() {
    val viewModel = createViewModel()
    application {
        Window(
            onCloseRequest = {
                viewModel.close()
                exitApplication()
            },
            title = "GenCards Voice/Text Simulator",
        ) {
            window.minimumSize = java.awt.Dimension(780, 720)
            SimulatorApp(viewModel)
        }
    }
}

private fun createViewModel(): SimulatorViewModel {
    val config = SimulatorConfig.fromEnvironment()
    val platform = currentPlatformEnvironment()
    val adbLocator = DefaultAdbLocator(manualPath = config.manualAdbPath)
    val adbClient = ProcessAdbClient(adbLocator)
    val bridgeClient = HttpBridgeClient(config)
    val preflight = DevicePreflight(adbClient, bridgeClient, config)
    val storage = Path.of(System.getProperty("user.home"), ".gencards-voice-simulator")
    val historyStore = JsonFileHistoryStore(storage.resolve("history.json"))
    val logger = SimulatorLogger(storage.resolve("logs").resolve("simulator.log"))

    return SimulatorViewModel(
        config = config,
        adbLocator = adbLocator,
        adbClient = adbClient,
        bridgeClient = bridgeClient,
        preflight = preflight,
        documentLoader = TextDocumentLoader(),
        historyStore = historyStore,
        logger = logger,
        platformEnvironment = platform,
    )
}
