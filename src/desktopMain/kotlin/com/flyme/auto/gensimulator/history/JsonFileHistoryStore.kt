package com.flyme.auto.gensimulator.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class JsonFileHistoryStore(
    private val file: Path,
    private val maxRecords: Int = 200,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) : HistoryStore {
    private val mutex = Mutex()
    private val serializer = ListSerializer(RequestRecord.serializer())

    override suspend fun load(): List<RequestRecord> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!Files.exists(file)) return@withContext emptyList()
            runCatching { json.decodeFromString(serializer, Files.readString(file, StandardCharsets.UTF_8)) }
                .getOrDefault(emptyList())
        }
    }

    override suspend fun append(record: RequestRecord) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = if (Files.exists(file)) {
                runCatching { json.decodeFromString(serializer, Files.readString(file, StandardCharsets.UTF_8)) }
                    .getOrDefault(emptyList())
            } else emptyList()
            write((listOf(record) + current).take(maxRecords))
        }
    }

    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { write(emptyList()) }
    }

    private fun write(records: List<RequestRecord>) {
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            json.encodeToString(serializer, records),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }
}
