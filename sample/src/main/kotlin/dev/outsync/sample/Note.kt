package dev.outsync.sample

import dev.outsync.EntityAdapter
import dev.outsync.PayloadCodec
import org.json.JSONObject

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val version: Long? = null,
)

object NoteAdapter : EntityAdapter<Note> {
    override fun idOf(entity: Note) = entity.id
    override fun versionOf(entity: Note) = entity.version
    override fun withVersion(entity: Note, version: Long) = entity.copy(version = version)
}

object NoteCodec : PayloadCodec<Note> {
    override fun encode(value: Note): String = value.toJson().toString()
    override fun decode(encoded: String): Note = JSONObject(encoded).toNote()
}

fun Note.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("body", body)
    .put("version", version ?: JSONObject.NULL)

fun JSONObject.toNote() = Note(
    id = getString("id"),
    title = getString("title"),
    body = getString("body"),
    version = if (isNull("version")) null else getLong("version"),
)
