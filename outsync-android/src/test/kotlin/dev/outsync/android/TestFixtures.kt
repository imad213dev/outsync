package dev.outsync.android

import dev.outsync.EntityAdapter
import dev.outsync.LocalStore
import dev.outsync.Mutation
import dev.outsync.PayloadCodec
import dev.outsync.PullResult
import dev.outsync.PushResult
import dev.outsync.RemoteChange
import dev.outsync.RemoteSource

data class Task(val id: String, val title: String, val version: Long? = null)

object TaskAdapter : EntityAdapter<Task> {
    override fun idOf(entity: Task) = entity.id
    override fun versionOf(entity: Task) = entity.version
    override fun withVersion(entity: Task, version: Long) = entity.copy(version = version)
}

object TaskCodec : PayloadCodec<Task> {
    override fun encode(value: Task) = listOf(value.id, value.title, value.version?.toString().orEmpty()).joinToString("\u0000")
    override fun decode(encoded: String): Task {
        val (id, title, version) = encoded.split("\u0000")
        return Task(id, title, version.toLongOrNull())
    }
}

class MemoryLocal : LocalStore<Task> {
    val rows = mutableMapOf<String, Task>()
    override suspend fun get(id: String) = rows[id]
    override suspend fun put(entity: Task) { rows[entity.id] = entity }
    override suspend fun delete(id: String) { rows.remove(id) }
}

class MemoryServer : RemoteSource<Task> {
    val rows = mutableMapOf<String, Task>()
    var online = true
    private var version = 1L

    override suspend fun push(mutation: Mutation<Task>): PushResult<Task> {
        if (!online) return PushResult.Retry()
        val current = rows[mutation.entityId]
        if (current?.version != mutation.baseVersion) return PushResult.Conflict(current)
        return when (mutation.kind) {
            Mutation.Kind.Upsert -> {
                val stored = mutation.payload!!.copy(version = version++)
                rows[stored.id] = stored
                PushResult.Accepted(stored)
            }
            Mutation.Kind.Delete -> {
                rows.remove(mutation.entityId)
                PushResult.Accepted(null)
            }
        }
    }

    override suspend fun pull(cursor: String?): PullResult<Task> {
        if (!online) throw IllegalStateException("offline")
        return PullResult(emptyList<RemoteChange<Task>>(), cursor)
    }
}
