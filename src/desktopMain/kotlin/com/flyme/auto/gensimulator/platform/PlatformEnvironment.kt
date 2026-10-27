package com.flyme.auto.gensimulator.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import kotlin.coroutines.resume

interface PlatformEnvironment {
    suspend fun selectTextFile(): Path?
    fun openDirectory(path: Path)
}

abstract class AwtPlatformEnvironment : PlatformEnvironment {
    override suspend fun selectTextFile(): Path? = suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            val dialog = FileDialog(null as Frame?, "Open text file", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name ->
                name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json", "yaml", "yml")
            }
            dialog.isVisible = true
            val selected = dialog.file?.let { fileName ->
                val directory = dialog.directory?.let(Path::of) ?: Path.of(".")
                directory.resolve(fileName)
            }
            dialog.dispose()
            if (continuation.isActive) continuation.resume(selected)
        }
    }

    override fun openDirectory(path: Path) {
        if (Desktop.isDesktopSupported()) {
            runCatching { Desktop.getDesktop().open(path.toFile()) }
        }
    }
}

class MacEnvironment : AwtPlatformEnvironment()
class WindowsEnvironment : AwtPlatformEnvironment()
class GenericDesktopEnvironment : AwtPlatformEnvironment()

fun currentPlatformEnvironment(osName: String = System.getProperty("os.name")): PlatformEnvironment = when {
    osName.lowercase().contains("mac") -> MacEnvironment()
    osName.lowercase().contains("win") -> WindowsEnvironment()
    else -> GenericDesktopEnvironment()
}
