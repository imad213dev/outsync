package dev.outsync.sample

import android.content.Context
import dev.outsync.Mutation
import dev.outsync.PullResult
import dev.outsync.PushResult
import dev.outsync.RemoteChange
import dev.outsync.RemoteSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class CloudSimulator(context: Context) : RemoteSource<Note> {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val rows = linkedMapOf<String, Note>()
    private val log = mutableListOf<JSONObject>()
    private var nextVersion = 1L

    val online = MutableStateFlow(true)

    init {
        restore()
    }

    override suspend fun push(mutation: Mutation<Note>): PushResult<Note> {
        networkRoundTrip()
        return lock.withLock {
            val current = rows[mutation.entityId]
            if (current?.version != mutation.baseVersion) return@withLock PushResult.Conflict(current)
            when (mutation.kind) {
                Mutation.Kind.Upsert -> PushResult.Accepted(store(mutation.payload!!))
                Mutation.Kind.Delete -> {
                    remove(mutation.entityId)
                    PushResult.Accepted(null)
                }
            }
        }
    }

    override suspend fun pull(cursor: String?): PullResult<Note> {
        networkRoundTrip()
        return lock.withLock {
            val from = cursor?.toIntOrNull() ?: 0
            val changes = log.drop(from).map { entry ->
                if (entry.has("deleted")) RemoteChange.Deleted(entry.getString("deleted"))
                else RemoteChange.Upserted(entry.getJSONObject("note").toNote())
            }
            PullResult(changes, log.size.toString())
        }
    }

    suspend fun editFromAnotherDevice(): Note? = lock.withLock {
        val target = rows.values.firstOrNull() ?: return@withLock null
        store(target.copy(body = "Edited on a tablet at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"))
    }

    private suspend fun networkRoundTrip() {
        delay(400)
        if (!online.value) throw IOException("No network (simulated)")
    }

    private fun store(note: Note): Note {
        val stored = note.copy(version = nextVersion++)
        rows[stored.id] = stored
        log += JSONObject().put("note", stored.toJson())
        persist()
        return stored
    }

    private fun remove(id: String) {
        rows.remove(id)
        log += JSONObject().put("deleted", id)
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putString("log", JSONArray(log).toString())
            .putLong("nextVersion", nextVersion)
            .apply()
    }

    private fun restore() {
        nextVersion = prefs.getLong("nextVersion", 1L)
        val raw = prefs.getString("log", null) ?: return
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            log += entry
            if (entry.has("deleted")) rows.remove(entry.getString("deleted"))
            else entry.getJSONObject("note").toNote().let { rows[it.id] = it }
        }
    }
}
