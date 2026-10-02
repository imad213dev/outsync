package dev.outsync

public data class SyncStatus(
    val isSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val lastSuccessAtMillis: Long? = null,
    val lastError: Throwable? = null,
)

public data class SyncReport(
    val pushed: Int = 0,
    val conflictsResolved: Int = 0,
    val rejected: Int = 0,
    val pulled: Int = 0,
    val error: Throwable? = null,
) {
    val isSuccess: Boolean get() = error == null
}

public sealed interface SyncEvent<out T : Any> {
    public data class Rejected<T : Any>(val mutation: Mutation<T>, val reason: String) : SyncEvent<T>

    public data class ConflictGaveUp<T : Any>(val mutation: Mutation<T>, val remote: T?) : SyncEvent<T>
}

public data class SyncConfig(
    val pushBatchSize: Int = 50,
    val maxConflictRetries: Int = 3,
) {
    init {
        require(pushBatchSize > 0) { "pushBatchSize must be > 0" }
        require(maxConflictRetries >= 0) { "maxConflictRetries must be >= 0" }
    }
}

public class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
