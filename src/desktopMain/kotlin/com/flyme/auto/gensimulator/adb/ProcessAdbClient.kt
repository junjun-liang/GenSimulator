package com.flyme.auto.gensimulator.adb

import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode
import com.flyme.auto.gensimulator.protocol.SimulatorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ProcessAdbClient(
    private val locator: AdbLocator,
    private val timeoutMs: Long = 10_000,
) : AdbClient {

    override suspend fun version(): AdbCommandResult = exec("version")

    override suspend fun listDevices(): List<DeviceInfo> {
        val result = exec("devices", "-l")
        requireSuccess(result, "adb devices failed")
        return DeviceParser.parse(result.stdout)
    }

    override suspend fun currentUserId(serial: String): Int? {
        val result = exec("-s", serial, "shell", "am", "get-current-user")
        return result.takeIf { it.isSuccess }
            ?.stdout
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
    }

    override suspend fun isPackageInstalled(serial: String, userId: Int, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val result = exec("-s", serial, "shell", "pm", "path", "--user", userId.toString(), packageName)
        return result.isSuccess && result.stdout.lineSequence().any { it.trim().startsWith("package:") }
    }

    override suspend fun forward(serial: String, localPort: Int, remotePort: Int): AdbCommandResult =
        exec("-s", serial, "forward", "tcp:$localPort", "tcp:$remotePort")

    override suspend fun removeForward(serial: String, localPort: Int): AdbCommandResult =
        exec("-s", serial, "forward", "--remove", "tcp:$localPort")

    override suspend fun startActivity(serial: String, userId: Int, component: String): AdbCommandResult =
        exec("-s", serial, "shell", "am", "start", "--user", userId.toString(), "-n", component)

    private suspend fun exec(vararg args: String): AdbCommandResult = withContext(Dispatchers.IO) {
        val adbPath = locator.locate() ?: throw SimulatorException(
            SimulatorErrorCode.ADB_NOT_FOUND,
            "adb executable was not found",
        )

        coroutineScope {
            try {
                val process = ProcessBuilder(listOf(adbPath.toString()) + args.toList()).start()
                val stdoutDeferred = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
                val stderrDeferred = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readText() } }
                val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

                if (!completed) {
                    process.destroy()
                    if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    return@coroutineScope AdbCommandResult(
                        exitCode = -1,
                        stdout = stdoutDeferred.await(),
                        stderr = stderrDeferred.await(),
                        timedOut = true,
                    )
                }

                AdbCommandResult(
                    exitCode = process.exitValue(),
                    stdout = stdoutDeferred.await(),
                    stderr = stderrDeferred.await(),
                )
            } catch (exception: SimulatorException) {
                throw exception
            } catch (exception: Exception) {
                throw SimulatorException(
                    SimulatorErrorCode.ADB_EXEC_FAILED,
                    "Failed to execute adb",
                    exception,
                )
            }
        }
    }

    private fun requireSuccess(result: AdbCommandResult, message: String) {
        if (!result.isSuccess) {
            throw SimulatorException(
                SimulatorErrorCode.ADB_EXEC_FAILED,
                "$message: ${result.stderr.ifBlank { result.stdout }.trim()}",
            )
        }
    }
}
