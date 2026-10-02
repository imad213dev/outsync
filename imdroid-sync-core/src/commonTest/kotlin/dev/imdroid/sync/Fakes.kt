package dev.imdroid.sync

data class Note(val id: String, val title: String, val body: String = "", val version: Long? = null)

object NoteAdapter : EntityAdapter<Note> {
    override fun idOf(entity: Note) = entity.id
    override fun versionOf(entity: Note) = entity.version
    override fun withVersion(entity: Note, version: Long) = entity.copy(version = version)
}

class FakeLocal : LocalStore<Note> {
    val rows = mutableMapOf<String, Note>()
    override suspend fun get(id: String) = rows[id]
    override suspend fun put(entity: Note) { rows[entity.id] = entity }
    override suspend fun delete(id: String) { rows.remove(id) }
}

class FakeServer : RemoteSource<Note> {
    val rows = mutableMapOf<String, Note>()
    private val log = mutableListOf<RemoteChange<Note>>()
    private var nextVersion = 1L
    var online = true
    var pushCount = 0
    var rejectTitles = setOf<String>()
    var pageSize = 100

    fun editRemotely(note: Note): Note {
        val stored = note.copy(version = nextVersion++)
        rows[note.id] = stored
        log += RemoteChange.Upserted(stored)
        return stored
    }

    fun deleteRemotely(id: String) {
        rows.remove(id)
        log += RemoteChange.Deleted(id)
    }

    override suspend fun push(mutation: Mutation<Note>): PushResult<Note> {
        if (!online) throw IllegalStateException("offline")
        pushCount++
        val current = rows[mutation.entityId]
        if (current?.version != mutation.baseVersion) return PushResult.Conflict(current)
        return when (mutation.kind) {
            Mutation.Kind.Upsert -> {
                val payload = mutation.payload!!
                if (payload.title in rejectTitles) return PushResult.Rejected("invalid title")
                PushResult.Accepted(editRemotely(payload))
            }
            Mutation.Kind.Delete -> {
                deleteRemotely(mutation.entityId)
                PushResult.Accepted(null)
            }
        }
    }

    override suspend fun pull(cursor: String?): PullResult<Note> {
        if (!online) throw IllegalStateException("offline")
        val from = cursor?.toInt() ?: 0
        val to = minOf(from + pageSize, log.size)
        return PullResult(log.subList(from, to).toList(), to.toString(), hasMore = to < log.size)
    }
}

class Harness(
    resolver: ConflictResolver<Note> = ConflictResolvers.serverWins(),
    config: SyncConfig = SyncConfig(),
) {
    val local = FakeLocal()
    val server = FakeServer()
    val outbox = InMemoryOutbox<Note>()
    private var ids = 0
    val engine = SyncEngine(
        adapter = NoteAdapter,
        local = local,
        remote = server,
        outbox = outbox,
        resolver = resolver,
        config = config,
        clock = { 1_000L },
        newId = { "m${ids++}" },
    )
}
