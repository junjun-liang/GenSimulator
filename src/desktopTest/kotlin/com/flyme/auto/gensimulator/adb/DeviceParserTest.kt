package com.flyme.auto.gensimulator.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceParserTest {
    @Test
    fun parsesSingleDeviceAndMetadata() {
        val result = DeviceParser.parse(
            """
            List of devices attached
            ABC123 device product:car model:ZEEKR_001 device:vehicle transport_id:1
            """.trimIndent()
        )

        assertEquals(1, result.size)
        assertEquals("ABC123", result.single().serial)
        assertEquals(DeviceState.DEVICE, result.single().state)
        assertEquals("ZEEKR 001", result.single().model)
        assertEquals("car", result.single().product)
    }

    @Test
    fun parsesOfflineUnauthorizedAndUnknown() {
        val result = DeviceParser.parse(
            """
            List of devices attached
            a offline
            b unauthorized
            c recovery
            """.trimIndent()
        )

        assertEquals(DeviceState.OFFLINE, result[0].state)
        assertEquals(DeviceState.UNAUTHORIZED, result[1].state)
        assertEquals(DeviceState.UNKNOWN, result[2].state)
        assertNull(result[0].model)
    }
}
