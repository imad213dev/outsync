package dev.outsync.android

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.outsync.Mutation
import dev.outsync.Outbox
import dev.outsync.PayloadCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class SqliteOutbox<T : Any>(
    context: Context,
    name: String,
    private val codec: PayloadCodec<T>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Outbox<T> {

    private val helper = Helper(context.applicationContext, "outsync-$name.db")

    override suspend fun enqueue(mutation: Mutation<T>): Unit = io {
        writableDatabase.insertOrThrow(TABLE, null, mutation.toValues())
    }

    override suspend fun pending(limit: Int): List<Mutation<T>> = io {
        readableDatabase.query(TABLE, null, null, null, null, null, "$SEQ ASC", limit.toString()).readAll()
    }

    override suspend fun remove(mutationId: String): Unit = io {
        writableDatabase.delete(TABLE, "$MUTATION_ID = ?", arrayOf(mutationId))
    }

    override suspend fun update(mutation: Mutation<T>): Unit = io {
        writableDatabase.update(TABLE, mutation.toValues(), "$MUTATION_ID = ?", arrayOf(mutation.mutationId))
    }

    override suspend fun pendingFor(entityId: String): List<Mutation<T>> = io {
        readableDatabase.query(TABLE, null, "$ENTITY_ID = ?", arrayOf(entityId), null, null, "$SEQ ASC").readAll()
    }

    override suspend fun size(): Int = io {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { it.moveToFirst(); it.getInt(0) }
    }

    public fun close() {
        helper.close()
    }

    private suspend fun <R> io(block: SQLiteOpenHelper.() -> R): R = withContext(dispatcher) { helper.block() }

    private fun Mutation<T>.toValues() = ContentValues().apply {
        put(MUTATION_ID, mutationId)
        put(ENTITY_ID, entityId)
        put(KIND, kind.name)
        put(PAYLOAD, payload?.let(codec::encode))
        put(BASE_VERSION, baseVersion)
        put(CREATED_AT, createdAtMillis)
        put(ATTEMPTS, attempts)
    }

    private fun Cursor.readAll(): List<Mutation<T>> = use {
        val result = ArrayList<Mutation<T>>(count)
        val id = getColumnIndexOrThrow(MUTATION_ID)
        val entity = getColumnIndexOrThrow(ENTITY_ID)
        val kind = getColumnIndexOrThrow(KIND)
        val payload = getColumnIndexOrThrow(PAYLOAD)
        val base = getColumnIndexOrThrow(BASE_VERSION)
        val created = getColumnIndexOrThrow(CREATED_AT)
        val attempts = getColumnIndexOrThrow(ATTEMPTS)
        while (moveToNext()) {
            result += Mutation(
                mutationId = getString(id),
                entityId = getString(entity),
                kind = Mutation.Kind.valueOf(getString(kind)),
                payload = if (isNull(payload)) null else codec.decode(getString(payload)),
                baseVersion = if (isNull(base)) null else getLong(base),
                createdAtMillis = getLong(created),
                attempts = getInt(attempts),
            )
        }
        result
    }

    private class Helper(context: Context, dbName: String) : SQLiteOpenHelper(context, dbName, null, VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $SEQ INTEGER PRIMARY KEY AUTOINCREMENT,
                    $MUTATION_ID TEXT NOT NULL UNIQUE,
                    $ENTITY_ID TEXT NOT NULL,
                    $KIND TEXT NOT NULL,
                    $PAYLOAD TEXT,
                    $BASE_VERSION INTEGER,
                    $CREATED_AT INTEGER NOT NULL,
                    $ATTEMPTS INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_${TABLE}_$ENTITY_ID ON $TABLE($ENTITY_ID, $SEQ)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val VERSION = 1
        const val TABLE = "mutations"
        const val SEQ = "seq"
        const val MUTATION_ID = "mutation_id"
        const val ENTITY_ID = "entity_id"
        const val KIND = "kind"
        const val PAYLOAD = "payload"
        const val BASE_VERSION = "base_version"
        const val CREATED_AT = "created_at"
        const val ATTEMPTS = "attempts"
    }
}
