package dev.imdroid.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class InMemoryOutbox<T : Any> : Outbox<T> {
    private val mutex = Mutex()
    private val items = mutableListOf<Mutation<T>>()

    override suspend fun enqueue(mutation: Mutation<T>): Unit = mutex.withLock { items += mutation }
    override suspend fun pending(limit: Int): List<Mutation<T>> = mutex.withLock { items.take(limit) }
    override suspend fun remove(mutationId: String): Unit = mutex.withLock { items.removeAll { it.mutationId == mutationId } }
    override suspend fun update(mutation: Mutation<T>): Unit = mutex.withLock {
        val i = items.indexOfFirst { it.mutationId == mutation.mutationId }
        if (i >= 0) items[i] = mutation
    }
    override suspend fun pendingFor(entityId: String): List<Mutation<T>> = mutex.withLock { items.filter { it.entityId == entityId } }
    override suspend fun size(): Int = mutex.withLock { items.size }
}

public class InMemoryCursorStore(private var cursor: String? = null) : CursorStore {
    override suspend fun read(): String? = cursor
    override suspend fun write(cursor: String?) { this.cursor = cursor }
}
