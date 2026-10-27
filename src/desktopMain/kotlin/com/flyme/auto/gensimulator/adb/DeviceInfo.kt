package com.flyme.auto.gensimulator.adb

enum class DeviceState {
    DEVICE,
    OFFLINE,
    UNAUTHORIZED,
    UNKNOWN,
}

data class DeviceInfo(
    val serial: String,
    val state: DeviceState,
    val model: String?,
    val product: String?,
    val device: String?,
)
