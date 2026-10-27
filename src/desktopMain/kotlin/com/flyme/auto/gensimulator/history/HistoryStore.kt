package com.flyme.auto.gensimulator.history

interface HistoryStore {
    suspend fun load(): List<RequestRecord>
    suspend fun append(record: RequestRecord)
    suspend fun clear()
}
