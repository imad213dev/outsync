package dev.outsync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    @Test
    fun offlineSaveIsVisibleImmediatelyAndPushedLater() = runTest {
        val h = Harness()
        h.server.online = false

        h.engine.save(Note("1", "Buy milk"))
        assertEquals("Buy milk", h.local.rows["1"]?.title)
        assertEquals(1, h.engine.status.value.pendingCount)

        val failed = h.engine.sync()
        assertFalse(failed.isSuccess)
        assertEquals(1, h.outbox.size(), "mutation must survive a failed sync")

        h.server.online = true
        val ok = h.engine.sync()
        assertTrue(ok.isSuccess)
        assertEquals(1, ok.pushed)
        assertEquals("Buy milk", h.server.rows["1"]?.title)
        assertEquals(h.server.rows["1"]?.version, h.local.rows["1"]?.version, "local gets server version")
        assertEquals(0, h.engine.status.value.pendingCount)
    }

    @Test
    fun manyOfflineEditsAreCoalescedIntoOnePush() = runTest {
        val h = Harness()
        repeat(10) { h.engine.save(Note("1", "draft $it")) }
        assertEquals(1, h.outbox.size())

        h.engine.sync()
        assertEquals(1, h.server.pushCount)
        assertEquals("draft 9", h.server.rows["1"]?.title)
    }

    @Test
    fun createThenDeleteOfflineSendsNothing() = runTest {
        val h = Harness()
        h.engine.save(Note("1", "temp"))
        h.engine.delete("1")

        h.engine.sync()
        assertEquals(0, h.server.pushCount)
        assertNull(h.local.rows["1"])
    }

    @Test
    fun deleteOfSyncedEntityReachesServer() = runTest {
        val h = Harness()
        h.engine.save(Note("1", "x"))
        h.engine.sync()

        h.engine.delete("1")
        h.engine.sync()
        assertNull(h.server.rows["1"])
        assertNull(h.local.rows["1"])
    }

    @Test
    fun consecutiveEditsAfterSyncDoNotFalselyConflict() = runTest {
        val h = Harness()
        h.engine.save(Note("1", "v1"))
        h.engine.sync()
        h.engine.save(Note("1", "v2"))
        h.engine.sync()
        h.engine.save(Note("1", "v3"))
        val report = h.engine.sync()

        assertEquals(0, report.conflictsResolved)
        assertEquals("v3", h.server.rows["1"]?.title)
    }

    @Test
    fun serverWinsDiscardsLocalChange() = runTest {
        val h = Harness(ConflictResolvers.serverWins())
        h.engine.save(Note("1", "original"))
        h.engine.sync()

        h.server.editRemotely(Note("1", "from tablet"))
        h.engine.save(Note("1", "from phone"))
        val report = h.engine.sync()

        assertEquals(1, report.conflictsResolved)
        assertEquals("from tablet", h.local.rows["1"]?.title)
        assertEquals("from tablet", h.server.rows["1"]?.title)
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun clientWinsOverwritesServer() = runTest {
        val h = Harness(ConflictResolvers.clientWins())
        h.engine.save(Note("1", "original"))
        h.engine.sync()

        h.server.editRemotely(Note("1", "from tablet"))
        h.engine.save(Note("1", "from phone"))
        h.engine.sync()

        assertEquals("from phone", h.server.rows["1"]?.title)
        assertEquals(h.server.rows["1"], h.local.rows["1"])
    }

    @Test
    fun mergeCombinesFieldsFromBothSides() = runTest {
        val h = Harness(ConflictResolvers.merge { local, remote -> local.copy(body = remote.body) })
        h.engine.save(Note("1", "title", "body"))
        h.engine.sync()

        h.server.editRemotely(Note("1", "title", "body edited on tablet"))
        h.engine.save(Note("1", "title edited on phone", "body"))
        h.engine.sync()

        val merged = h.server.rows["1"]!!
        assertEquals("title edited on phone", merged.title)
        assertEquals("body edited on tablet", merged.body)
        assertEquals(merged, h.local.rows["1"])
    }

    @Test
    fun clientWinsRecreatesEntityDeletedRemotely() = runTest {
        val h = Harness(ConflictResolvers.clientWins())
        h.engine.save(Note("1", "keep me"))
        h.engine.sync()

        h.server.deleteRemotely("1")
        h.engine.save(Note("1", "keep me, edited"))
        h.engine.sync()

        assertEquals("keep me, edited", h.server.rows["1"]?.title)
    }

    @Test
    fun endlessConflictGivesUpAndEmitsEvent() = runTest {
        val h = Harness(ConflictResolvers.clientWins(), SyncConfig(maxConflictRetries = 2))
        val hostile = object : RemoteSource<Note> by h.server {
            override suspend fun push(mutation: Mutation<Note>): PushResult<Note> {
                h.server.editRemotely(Note(mutation.entityId, "server moved on"))
                return h.server.push(mutation)
            }
        }
        val engine = SyncEngine(NoteAdapter, h.local, hostile, h.outbox, resolver = ConflictResolvers.clientWins(),
            config = SyncConfig(maxConflictRetries = 2))

        engine.save(Note("1", "mine"))
        val report = engine.sync()

        assertTrue(report.isSuccess)
        assertIs<SyncEvent.ConflictGaveUp<Note>>(engine.events.first(), "event buffered until collected")
        assertEquals(0, h.outbox.size())
        assertEquals("server moved on", h.local.rows["1"]?.title)
    }

    @Test
    fun rejectedMutationIsDroppedAndReported() = runTest {
        val h = Harness()
        h.server.rejectTitles = setOf("bad")
        h.engine.save(Note("1", "bad"))
        h.engine.save(Note("2", "good"))
        val report = h.engine.sync()

        assertEquals(1, report.rejected)
        assertEquals(1, report.pushed, "a rejection must not block the rest of the queue")
        assertIs<SyncEvent.Rejected<Note>>(h.engine.events.first())
        assertEquals("good", h.server.rows["2"]?.title)
    }

    @Test
    fun pushOrderIsPreservedAcrossFailures() = runTest {
        val h = Harness()
        h.engine.save(Note("a", "1"))
        h.engine.save(Note("b", "2"))
        h.engine.save(Note("c", "3"))

        var calls = 0
        val flaky = object : RemoteSource<Note> by h.server {
            override suspend fun push(mutation: Mutation<Note>): PushResult<Note> {
                if (++calls == 2) return PushResult.Retry()
                return h.server.push(mutation)
            }
        }
        val engine = SyncEngine(NoteAdapter, h.local, flaky, h.outbox)

        assertFalse(engine.sync().isSuccess)
        assertEquals(setOf("a"), h.server.rows.keys, "must stop at the failure, not skip ahead")
        assertTrue(engine.sync().isSuccess)
        assertEquals(setOf("a", "b", "c"), h.server.rows.keys)
    }

    @Test
    fun pullAppliesRemoteChangesAcrossPages() = runTest {
        val h = Harness()
        h.server.pageSize = 2
        repeat(5) { h.server.editRemotely(Note("$it", "remote $it")) }
        h.server.deleteRemotely("0")

        val report = h.engine.sync()
        assertEquals(6, report.pulled)
        assertEquals(setOf("1", "2", "3", "4"), h.local.rows.keys)

        assertEquals(0, h.engine.sync().pulled, "cursor must be persisted")
    }

    @Test
    fun pullNeverOverwritesEditMadeWhileSyncing() = runTest {
        val h = Harness()
        lateinit var engine: SyncEngine<Note>
        var raced = false
        val racing = object : RemoteSource<Note> by h.server {
            override suspend fun pull(cursor: String?): PullResult<Note> {
                val page = h.server.pull(cursor)
                if (!raced) {
                    raced = true
                    engine.save(Note("1", "typed during sync"))
                }
                return page
            }
        }
        engine = SyncEngine(NoteAdapter, h.local, racing, h.outbox)
        h.server.editRemotely(Note("1", "remote"))

        engine.sync()
        assertEquals("typed during sync", h.local.rows["1"]?.title, "remote must not clobber the user's edit")
        assertEquals(1, h.outbox.size())

        engine.sync()
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun statusReportsPendingAndSuccess() = runTest {
        val h = Harness()
        h.engine.save(Note("1", "a"))
        h.engine.save(Note("2", "b"))
        assertEquals(2, h.engine.status.value.pendingCount)

        h.engine.sync()
        val status = h.engine.status.value
        assertEquals(0, status.pendingCount)
        assertFalse(status.isSyncing)
        assertEquals(1_000L, status.lastSuccessAtMillis)
        assertNull(status.lastError)
    }
}
