package com.flyme.auto.gensimulator.config

import java.nio.file.Path

/** Runtime endpoints are centralized here so package/component values never leak through UI code. */
data class SimulatorConfig(
    val genCardsPackage: String,
    val bridgePackage: String,
    val bridgeBootstrapActivityComponent: String = "com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity",
    val bridgePort: Int = 39091,
    val bridgeDevicePort: Int = 39091,
    val expectedProtocolVersion: Int = 1,
    val expectedContractVersion: Int? = 2,
    val maxQueryChars: Int = 60_000,
    val manualAdbPath: Path? = null,
) {
    val isBridgeConfigured: Boolean get() = bridgePackage.isNotBlank()

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): SimulatorConfig {
            fun value(name: String): String? =
                System.getProperty(name.lowercase().replace('_', '.'))?.takeIf { it.isNotBlank() }
                    ?: env[name]?.takeIf { it.isNotBlank() }

            return SimulatorConfig(
                genCardsPackage = value("GENCARDS_PACKAGE") ?: "com.flyme.auto.gencards",
                bridgePackage = value("GENCARDS_BRIDGE_PACKAGE") ?: "com.flyme.auto.gensimulator.bridge",
                bridgeBootstrapActivityComponent = value("GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT")
                    ?: "com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity",
                bridgePort = value("GENCARDS_BRIDGE_PORT")?.toIntOrNull() ?: 39091,
                expectedProtocolVersion = value("GENCARDS_PROTOCOL_VERSION")?.toIntOrNull() ?: 1,
                expectedContractVersion = value("GENCARDS_CONTRACT_VERSION")?.toIntOrNull() ?: 2,
                maxQueryChars = (value("GENCARDS_MAX_QUERY_CHARS")?.toIntOrNull() ?: 60_000)
                    .coerceIn(1, 60_000),
                manualAdbPath = value("ADB_PATH")?.let(Path::of),
            )
        }
    }
}
