package com.flyme.auto.gensimulator.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

class SimulatorLogger(
    private val logFile: Path,
    private val maxInMemoryLines: Int = 300,
) {
    private val lines = ArrayDeque<String>()
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    suspend fun log(category: String, message: String) {
        val line = "${LocalTime.now().format(formatter)} $category $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > maxInMemoryLines) lines.removeFirst()
        }
        withContext(Dispatchers.IO) {
            Files.createDirectories(logFile.parent)
            Files.writeString(
                logFile,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    fun logDirectory(): Path = logFile.parent
}
