package com.flyme.auto.gensimulator.adb

data class AdbCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = !timedOut && exitCode == 0
}
