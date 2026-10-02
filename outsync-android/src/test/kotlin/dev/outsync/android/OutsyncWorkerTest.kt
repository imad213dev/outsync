package dev.outsync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.outsync.InMemoryOutbox
import dev.outsync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutsyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MemoryServer()
    private val engine = SyncEngine(TaskAdapter, MemoryLocal(), server, InMemoryOutbox())

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        Outsync.register(context, "tasks", engine, periodicInterval = null)
    }

    @After
    fun tearDown() = Outsync.unregister(context, "tasks")

    private fun runWorker(name: String = "tasks") = runBlocking {
        TestListenableWorkerBuilder<OutsyncWorker>(context)
            .setInputData(workDataOf(Outsync.KEY_NAME to name))
            .build()
            .doWork()
    }

    @Test
    fun workerPushesPendingChanges() {
        runBlocking { engine.save(Task("1", "from worker")) }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("from worker", server.rows["1"]?.title)
    }

    @Test
    fun workerAsksForRetryWhenOffline() {
        server.online = false
        runBlocking { engine.save(Task("1", "x")) }

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertTrue(server.rows.isEmpty())
    }

    @Test
    fun workerFailsCleanlyWhenEngineIsNotRegistered() {
        assertEquals(ListenableWorker.Result.failure(), runWorker("unknown"))
    }
}
