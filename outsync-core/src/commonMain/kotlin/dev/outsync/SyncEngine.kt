package dev.outsync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public class SyncEngine<T : Any>(
    private val adapter: EntityAdapter<T>,
    private val local: LocalStore<T>,
    private val remote: RemoteSource<T>,
    private val outbox: Outbox<T> = InMemoryOutbox(),
    private val cursorStore: CursorStore = InMemoryCursorStore(),
    private val resolver: ConflictResolver<T> = ConflictResolvers.serverWins(),
    private val config: SyncConfig = SyncConfig(),
    private val clock: () -> Long = ::systemNowMillis,
    private val newId: () -> String = ::randomId,
) {
    private val writeLock = Mutex()

    private val syncLock = Mutex()

    private var inFlightId: String? = null

    private val _status = MutableStateFlow(SyncStatus())
    public val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _events = Channel<SyncEvent<T>>(Channel.UNLIMITED)

    public val events: Flow<SyncEvent<T>> = _events.receiveAsFlow()

    public suspend fun save(entity: T) {
        writeLock.withLock {
            val id = adapter.idOf(entity)
            val knownVersion = local.get(id)?.let(adapter::versionOf) ?: adapter.versionOf(entity)
            local.put(if (knownVersion != null) adapter.withVersion(entity, knownVersion) else entity)
            enqueueCoalesced(id, Mutation.Kind.Upsert, entity, knownVersion)
        }
        refreshPending()
    }

    public suspend fun delete(id: String) {
        writeLock.withLock {
            val knownVersion = local.get(id)?.let(adapter::versionOf)
            local.delete(id)
            enqueueCoalesced(id, Mutation.Kind.Delete, null, knownVersion)
        }
        refreshPending()
    }

    public suspend fun sync(): SyncReport = syncLock.withLock {
        _status.update { it.copy(isSyncing = true) }
        var report = SyncReport()
        try {
            report = push(report)
            if (report.isSuccess) report = pull(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report = report.copy(error = e)
        } finally {
            inFlightId = null
            val pending = outbox.size()
            _status.update {
                it.copy(
                    isSyncing = false,
                    pendingCount = pending,
                    lastError = report.error,
                    lastSuccessAtMillis = if (report.isSuccess) clock() else it.lastSuccessAtMillis,
                )
            }
        }
        report
    }

    private suspend fun enqueueCoalesced(id: String, kind: Mutation.Kind, payload: T?, baseVersion: Long?) {
        val last = outbox.pendingFor(id).lastOrNull()?.takeIf { it.mutationId != inFlightId }
        if (last == null) {
            outbox.enqueue(Mutation(newId(), id, kind, payload, baseVersion, clock()))
            return
        }
        val createdOfflineAndDeleted = kind == Mutation.Kind.Delete && last.baseVersion == null &&
            outbox.pendingFor(id).size == 1
        if (createdOfflineAndDeleted) {
            outbox.remove(last.mutationId)
        } else {
            outbox.update(last.copy(kind = kind, payload = payload))
        }
    }

    private suspend fun push(start: SyncReport): SyncReport {
        var report = start
        while (true) {
            val batch = outbox.pending(config.pushBatchSize)
            if (batch.isEmpty()) return report
            for (mutation in batch) {
                inFlightId = mutation.mutationId
                val result = try {
                    remote.push(mutation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    PushResult.Retry(e)
                }
                inFlightId = null
                report = writeLock.withLock {
                    val current = outbox.pendingFor(mutation.entityId).firstOrNull { it.mutationId == mutation.mutationId }
                        ?: mutation
                    handlePushResult(current, result, report)
                }
                if (!report.isSuccess) return report
            }
            refreshPending()
        }
    }

    private suspend fun handlePushResult(m: Mutation<T>, result: PushResult<T>, report: SyncReport): SyncReport =
        when (result) {
            is PushResult.Accepted -> {
                outbox.remove(m.mutationId)
                val newVersion = result.serverEntity?.let(adapter::versionOf)
                applyServerVersion(m.entityId, result.serverEntity, newVersion)
                report.copy(pushed = report.pushed + 1)
            }

            is PushResult.Conflict -> resolveConflict(m, result.serverEntity, report)

            is PushResult.Retry -> {
                outbox.update(m.copy(attempts = m.attempts + 1))
                report.copy(error = SyncException("Push failed, will retry", result.cause))
            }

            is PushResult.Rejected -> {
                outbox.remove(m.mutationId)
                _events.trySend(SyncEvent.Rejected(m, result.reason))
                report.copy(rejected = report.rejected + 1)
            }
        }

    private suspend fun applyServerVersion(entityId: String, serverEntity: T?, newVersion: Long?) {
        val later = outbox.pendingFor(entityId)
        later.forEach { outbox.update(it.copy(baseVersion = newVersion)) }
        if (later.isEmpty()) {
            if (serverEntity != null) local.put(serverEntity) else local.delete(entityId)
        } else if (newVersion != null) {
            local.get(entityId)?.let { local.put(adapter.withVersion(it, newVersion)) }
        }
    }

    private suspend fun resolveConflict(m: Mutation<T>, remoteEntity: T?, report: SyncReport): SyncReport {
        val remoteVersion = remoteEntity?.let(adapter::versionOf)
        val takeRemote = suspend {
            outbox.pendingFor(m.entityId).forEach { outbox.remove(it.mutationId) }
            if (remoteEntity != null) local.put(remoteEntity) else local.delete(m.entityId)
        }

        if (m.attempts >= config.maxConflictRetries) {
            takeRemote()
            _events.trySend(SyncEvent.ConflictGaveUp(m, remoteEntity))
            return report.copy(conflictsResolved = report.conflictsResolved + 1)
        }

        val rebased = m.copy(baseVersion = remoteVersion, attempts = m.attempts + 1)
        when (val resolution = resolver.resolve(m.payload, remoteEntity)) {
            Resolution.TakeRemote -> takeRemote()

            Resolution.TakeLocal ->
                if (m.kind == Mutation.Kind.Delete && remoteEntity == null) outbox.remove(m.mutationId)
                else outbox.update(rebased)

            is Resolution.Merged -> {
                val merged = if (remoteVersion != null) adapter.withVersion(resolution.value, remoteVersion) else resolution.value
                local.put(merged)
                outbox.update(rebased.copy(kind = Mutation.Kind.Upsert, payload = merged))
            }
        }
        return report.copy(conflictsResolved = report.conflictsResolved + 1)
    }

    private suspend fun pull(start: SyncReport): SyncReport {
        var report = start
        while (true) {
            val page = remote.pull(cursorStore.read())
            writeLock.withLock {
                for (change in page.changes) {
                    val id = when (change) {
                        is RemoteChange.Upserted -> adapter.idOf(change.entity)
                        is RemoteChange.Deleted -> change.entityId
                    }
                    if (outbox.pendingFor(id).isNotEmpty()) continue
                    when (change) {
                        is RemoteChange.Upserted -> local.put(change.entity)
                        is RemoteChange.Deleted -> local.delete(id)
                    }
                    report = report.copy(pulled = report.pulled + 1)
                }
                cursorStore.write(page.nextCursor)
            }
            if (!page.hasMore) return report
        }
    }

    private suspend fun refreshPending() {
        val pending = outbox.size()
        _status.update { it.copy(pendingCount = pending) }
    }
}

@OptIn(ExperimentalTime::class)
private fun systemNowMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalUuidApi::class)
private fun randomId(): String = Uuid.random().toString()
