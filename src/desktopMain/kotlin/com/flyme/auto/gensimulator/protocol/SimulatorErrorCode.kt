package com.flyme.auto.gensimulator.protocol

enum class SimulatorErrorCode {
    ADB_NOT_FOUND,
    ADB_EXEC_FAILED,
    DEVICE_NOT_CONNECTED,
    DEVICE_UNAUTHORIZED,
    DEVICE_OFFLINE,
    MULTIPLE_DEVICES_NOT_SELECTED,
    GENCARDS_NOT_INSTALLED,
    BRIDGE_NOT_INSTALLED,
    BRIDGE_START_FAILED,
    BRIDGE_HEALTH_TIMEOUT,
    BRIDGE_UNREACHABLE,
    GENCARDS_BIND_FAILED,
    GENCARDS_PERMISSION_DENIED,
    GENCARDS_BINDER_DIED,
    GENCARDS_CONNECT_TIMEOUT,
    QUERY_EMPTY,
    QUERY_TOO_LARGE,
    PROTOCOL_VERSION_MISMATCH,
    CONTRACT_VERSION_MISMATCH,
    RESPONSE_REQUEST_ID_MISMATCH,
    INGRESS_ACCEPTED,
    INGRESS_REJECTED,
    CONFIG_INVALID,
    FILE_READ_FAILED,
    UNKNOWN_ERROR;

    companion object {
        fun fromWireCode(code: String): SimulatorErrorCode =
            entries.firstOrNull { it.name == code } ?: UNKNOWN_ERROR
    }
}

class SimulatorException(
    val code: SimulatorErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
