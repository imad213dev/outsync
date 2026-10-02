package dev.outsync.android

import android.content.Context
import dev.outsync.ConflictResolver
import dev.outsync.ConflictResolvers
import dev.outsync.EntityAdapter
import dev.outsync.LocalStore
import dev.outsync.PayloadCodec
import dev.outsync.RemoteSource
import dev.outsync.SyncConfig
import dev.outsync.SyncEngine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public fun <T : Any> outsyncEngine(
    context: Context,
    name: String,
    adapter: EntityAdapter<T>,
    local: LocalStore<T>,
    remote: RemoteSource<T>,
    codec: PayloadCodec<T>,
    resolver: ConflictResolver<T> = ConflictResolvers.serverWins(),
    config: SyncConfig = SyncConfig(),
    autoSync: Boolean = true,
    periodicInterval: Duration? = 15.minutes,
    syncOnStart: Boolean = true,
): SyncEngine<T> {
    val engine = SyncEngine(
        adapter = adapter,
        local = local,
        remote = remote,
        outbox = SqliteOutbox(context, name, codec),
        cursorStore = PreferencesCursorStore(context, name),
        resolver = resolver,
        config = config,
    )
    if (autoSync) Outsync.register(context, name, engine, periodicInterval, syncOnStart)
    return engine
}
