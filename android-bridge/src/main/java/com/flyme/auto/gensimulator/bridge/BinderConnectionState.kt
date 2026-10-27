package com.flyme.auto.gensimulator.bridge

internal enum class BinderConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    GENCARDS_NOT_INSTALLED,
    PERMISSION_DENIED,
    BINDER_DIED,
    BIND_FAILED,
    NULL_BINDING,
}

internal fun BinderConnectionState.failureCode(): String = when (this) {
    BinderConnectionState.GENCARDS_NOT_INSTALLED -> BridgeErrorCode.GENCARDS_NOT_INSTALLED
    BinderConnectionState.PERMISSION_DENIED -> BridgeErrorCode.GENCARDS_PERMISSION_DENIED
    BinderConnectionState.BINDER_DIED -> BridgeErrorCode.GENCARDS_BINDER_DIED
    else -> BridgeErrorCode.GENCARDS_BIND_FAILED
}
