package com.flyme.auto.gensimulator.document

import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode
import com.flyme.auto.gensimulator.protocol.SimulatorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class TextDocumentLoader {
    private val supportedExtensions = setOf("txt", "md", "json", "yaml", "yml")

    suspend fun load(path: Path): String = withContext(Dispatchers.IO) {
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension !in supportedExtensions) {
            throw SimulatorException(
                SimulatorErrorCode.FILE_READ_FAILED,
                "Unsupported text file type: .$extension",
            )
        }
        try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (exception: Exception) {
            throw SimulatorException(
                SimulatorErrorCode.FILE_READ_FAILED,
                "Failed to read ${path.fileName} as UTF-8",
                exception,
            )
        }
    }
}
