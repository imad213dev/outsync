package dev.imdroid.sync

public fun interface ConflictResolver<T : Any> {
    public suspend fun resolve(local: T?, remote: T?): Resolution<T>
}

public sealed interface Resolution<out T : Any> {
    public data object TakeRemote : Resolution<Nothing>

    public data object TakeLocal : Resolution<Nothing>

    public data class Merged<T : Any>(val value: T) : Resolution<T>
}

public object ConflictResolvers {
    public fun <T : Any> serverWins(): ConflictResolver<T> = ConflictResolver { _, _ -> Resolution.TakeRemote }

    public fun <T : Any> clientWins(): ConflictResolver<T> = ConflictResolver { _, _ -> Resolution.TakeLocal }

    public fun <T : Any> merge(merge: suspend (local: T, remote: T) -> T): ConflictResolver<T> =
        ConflictResolver { local, remote ->
            if (local == null || remote == null) Resolution.TakeRemote
            else Resolution.Merged(merge(local, remote))
        }
}
