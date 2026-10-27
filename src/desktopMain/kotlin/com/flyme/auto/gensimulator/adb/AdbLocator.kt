package com.flyme.auto.gensimulator.adb

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

fun interface AdbLocator {
    fun locate(): Path?
}

class DefaultAdbLocator(
    private val manualPath: Path? = null,
    private val env: Map<String, String> = System.getenv(),
    private val osName: String = System.getProperty("os.name"),
    private val userHome: String = System.getProperty("user.home"),
) : AdbLocator {

    override fun locate(): Path? = candidates().firstOrNull(::isUsable)

    internal fun candidates(): List<Path> {
        val windows = osName.lowercase().contains("win")
        val adbName = if (windows) "adb.exe" else "adb"
        val result = linkedSetOf<Path>()

        manualPath?.let(result::add)

        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
            .mapNotNull(env::get)
            .filter(String::isNotBlank)
            .map { Path.of(it, "platform-tools", adbName) }
            .forEach(result::add)

        env["PATH"]
            ?.split(File.pathSeparator)
            ?.filter(String::isNotBlank)
            ?.map { Path.of(it, adbName) }
            ?.forEach(result::add)

        if (windows) {
            env["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let {
                result.add(Path.of(it, "Android", "Sdk", "platform-tools", "adb.exe"))
            }
        } else {
            result.add(Path.of(userHome, "Library", "Android", "sdk", "platform-tools", "adb"))
        }

        return result.toList()
    }

    private fun isUsable(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        return osName.lowercase().contains("win") || Files.isExecutable(path)
    }
}
