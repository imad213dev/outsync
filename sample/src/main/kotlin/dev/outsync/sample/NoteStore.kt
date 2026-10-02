package dev.outsync.sample

import android.content.Context
import dev.outsync.LocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

class NoteStore(context: Context) : LocalStore<Note> {
    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
    private val rows = MutableStateFlow(load())

    val notes: StateFlow<Map<String, Note>> = rows.asStateFlow()

    override suspend fun get(id: String): Note? = rows.value[id]

    override suspend fun put(entity: Note) = mutate { it + (entity.id to entity) }

    override suspend fun delete(id: String) = mutate { it - id }

    private fun mutate(change: (Map<String, Note>) -> Map<String, Note>) {
        rows.update(change)
        val json = JSONArray().apply { rows.value.values.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private fun load(): Map<String, Note> {
        val raw = prefs.getString(KEY, null) ?: return linkedMapOf()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getJSONObject(it).toNote() }.associateBy { it.id }
    }

    private companion object {
        const val KEY = "rows"
    }
}
