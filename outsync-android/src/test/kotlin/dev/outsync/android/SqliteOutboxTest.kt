package dev.outsync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.outsync.Mutation
import dev.outsync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SqliteOutboxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<SqliteOutbox<Task>>()

    private fun outbox(name: String = "tasks") =
        SqliteOutbox(context, name, TaskCodec, Dispatchers.Unconfined).also { opened += it }

    @After
    fun tearDown() = opened.forEach { it.close() }

    private fun mutation(id: String, entity: String, title: String? = "t", base: Long? = null) = Mutation(
        mutationId = id,
        entityId = entity,
        kind = if (title == null) Mutation.Kind.Delete else Mutation.Kind.Upsert,
        payload = title?.let { Task(entity, it, base) },
        baseVersion = base,
        createdAtMillis = 42L,
    )

    @Test
    fun roundTripsEveryField() = runTest {
        val box = outbox()
        val upsert = mutation("m1", "a", "hello", base = 7)
        val delete = mutation("m2", "b", title = null)
        box.enqueue(upsert)
        box.enqueue(delete)

        assertEquals(listOf(upsert, delete), box.pending(10))
    }

    @Test
    fun survivesProcessDeath() = runTest {
        val first = outbox()
        first.enqueue(mutation("m1", "a", "one"))
        first.enqueue(mutation("m2", "b", "two"))
        first.enqueue(mutation("m3", "a", "three"))
        first.close()

        val reopened = outbox()
        assertEquals(listOf("m1", "m2", "m3"), reopened.pending(10).map { it.mutationId })
        assertEquals(listOf("m1", "m3"), reopened.pendingFor("a").map { it.mutationId })
        assertEquals(3, reopened.size())
    }

    @Test
    fun updateKeepsQueuePosition() = runTest {
        val box = outbox()
        box.enqueue(mutation("m1", "a", "old"))
        box.enqueue(mutation("m2", "b"))
        box.update(mutation("m1", "a", "new").copy(attempts = 2))

        val head = box.pending(10).first()
        assertEquals("m1", head.mutationId)
        assertEquals("new", head.payload?.title)
        assertEquals(2, head.attempts)
    }

    @Test
    fun removeAndLimit() = runTest {
        val box = outbox()
        repeat(5) { box.enqueue(mutation("m$it", "e$it")) }
        box.remove("m0")

        assertEquals(listOf("m1", "m2"), box.pending(2).map { it.mutationId })
        assertEquals(4, box.size())
        assertTrue(box.pendingFor("e0").isEmpty())
    }

    @Test
    fun separateNamesAreIsolated() = runTest {
        outbox("tasks").enqueue(mutation("m1", "a"))
        assertEquals(0, outbox("notes").size())
    }

    @Test
    fun offlineEditsSurviveRestartAndSyncLater() = runTest {
        val local = MemoryLocal()
        val server = MemoryServer().apply { online = false }

        val beforeKill = SyncEngine(TaskAdapter, local, server, outbox())
        beforeKill.save(Task("1", "written offline"))
        beforeKill.save(Task("2", "also offline"))
        beforeKill.sync()
        opened.forEach { it.close() }

        server.online = true
        val afterRestart = SyncEngine(TaskAdapter, local, server, outbox())
        afterRestart.refreshStatus()
        assertEquals(2, afterRestart.status.value.pendingCount)

        val report = afterRestart.sync()
        assertTrue(report.isSuccess)
        assertEquals(setOf("1", "2"), server.rows.keys)
        assertEquals(0, afterRestart.status.value.pendingCount)
        assertNull(afterRestart.status.value.lastError)
    }
}
