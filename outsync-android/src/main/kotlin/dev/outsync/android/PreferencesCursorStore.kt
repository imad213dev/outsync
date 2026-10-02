package dev.outsync.android

import android.content.Context
import dev.outsync.CursorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class PreferencesCursorStore(context: Context, name: String) : CursorStore {
    private val prefs = context.applicationContext.getSharedPreferences("outsync", Context.MODE_PRIVATE)
    private val key = "cursor:$name"

    override suspend fun read(): String? = withContext(Dispatchers.IO) { prefs.getString(key, null) }

    override suspend fun write(cursor: String?): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, cursor).commit()
    }
}
