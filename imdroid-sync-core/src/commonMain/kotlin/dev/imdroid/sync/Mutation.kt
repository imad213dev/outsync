package dev.imdroid.sync

public data class Mutation<T : Any>(
    val mutationId: String,
    val entityId: String,
    val kind: Kind,
    val payload: T?,
    val baseVersion: Long?,
    val createdAtMillis: Long,
    val attempts: Int = 0,
) {
    public enum class Kind { Upsert, Delete }
}
