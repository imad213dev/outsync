package dev.imdroid.sync

public interface LocalStore<T : Any> {
    public suspend fun get(id: String): T?
    public suspend fun put(entity: T)
    public suspend fun delete(id: String)
}

public interface Outbox<T : Any> {
    public suspend fun enqueue(mutation: Mutation<T>)
    public suspend fun pending(limit: Int): List<Mutation<T>>
    public suspend fun remove(mutationId: String)
    public suspend fun update(mutation: Mutation<T>)
    public suspend fun pendingFor(entityId: String): List<Mutation<T>>
    public suspend fun size(): Int
}

public interface CursorStore {
    public suspend fun read(): String?
    public suspend fun write(cursor: String?)
}
