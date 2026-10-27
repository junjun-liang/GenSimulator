package com.flyme.auto.gensimulator.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatorConfigTest {
    @Test
    fun builtInBridgeDefaultsCloseRepositoryOwnedRuntime() {
        val config = SimulatorConfig.fromEnvironment(emptyMap())
        assertEquals("com.flyme.auto.gensimulator.bridge", config.bridgePackage)
        assertEquals(
            "com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity",
            config.bridgeBootstrapActivityComponent,
        )
        assertEquals(1, config.expectedProtocolVersion)
        assertEquals(2, config.expectedContractVersion)
        assertTrue(config.isBridgeConfigured)
    }

    @Test
    fun environmentCanOverrideBridgeForDiagnostics() {
        val config = SimulatorConfig.fromEnvironment(
            mapOf(
                "GENCARDS_BRIDGE_PACKAGE" to "custom.bridge",
                "GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT" to "custom.bridge/.Bootstrap",
                "GENCARDS_CONTRACT_VERSION" to "7",
            )
        )
        assertEquals("custom.bridge", config.bridgePackage)
        assertEquals("custom.bridge/.Bootstrap", config.bridgeBootstrapActivityComponent)
        assertEquals(7, config.expectedContractVersion)
    }
}
