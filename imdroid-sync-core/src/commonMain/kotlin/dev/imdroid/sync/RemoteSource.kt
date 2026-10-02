package dev.imdroid.sync

public interface RemoteSource<T : Any> {
    public suspend fun push(mutation: Mutation<T>): PushResult<T>
    public suspend fun pull(cursor: String?): PullResult<T>
}

public sealed interface PushResult<out T : Any> {
    public data class Accepted<T : Any>(val serverEntity: T?) : PushResult<T>

    public data class Conflict<T : Any>(val serverEntity: T?) : PushResult<T>

    public data class Retry(val cause: Throwable? = null) : PushResult<Nothing>

    public data class Rejected(val reason: String) : PushResult<Nothing>
}

public data class PullResult<T : Any>(
    val changes: List<RemoteChange<T>>,
    val nextCursor: String?,
    val hasMore: Boolean = false,
)

public sealed interface RemoteChange<out T : Any> {
    public data class Upserted<T : Any>(val entity: T) : RemoteChange<T>
    public data class Deleted(val entityId: String) : RemoteChange<Nothing>
}
