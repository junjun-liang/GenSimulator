package com.flyme.auto.gensimulator.adb

interface AdbClient {
    suspend fun version(): AdbCommandResult
    suspend fun listDevices(): List<DeviceInfo>
    /** The active Android user owns the visible application profile on multi-user head units. */
    suspend fun currentUserId(serial: String): Int?
    suspend fun isPackageInstalled(serial: String, userId: Int, packageName: String): Boolean
    suspend fun forward(serial: String, localPort: Int, remotePort: Int): AdbCommandResult
    suspend fun removeForward(serial: String, localPort: Int): AdbCommandResult
    suspend fun startActivity(serial: String, userId: Int, component: String): AdbCommandResult
}
