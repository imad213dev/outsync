package dev.outsync.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.outsync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public object Outsync {
    internal const val KEY_NAME = "outsync.name"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engines = ConcurrentHashMap<String, SyncEngine<*>>()
    private val watchers = ConcurrentHashMap<String, Job>()

    public fun register(
        context: Context,
        name: String,
        engine: SyncEngine<*>,
        periodicInterval: Duration? = 15.minutes,
    ) {
        val app = context.applicationContext
        engines[name] = engine
        watchers.remove(name)?.cancel()
        watchers[name] = scope.launch {
            engine.refreshStatus()
            var last = 0
            engine.status.map { it.pendingCount }.distinctUntilChanged().collect { pending ->
                if (pending > last) requestSync(app, name)
                last = pending
            }
        }
        if (periodicInterval != null) schedulePeriodic(app, name, periodicInterval)
        else WorkManager.getInstance(app).cancelUniqueWork(periodicWorkName(name))
    }

    public fun unregister(context: Context, name: String) {
        engines.remove(name)
        watchers.remove(name)?.cancel()
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(oneTimeWorkName(name))
        wm.cancelUniqueWork(periodicWorkName(name))
    }

    public fun requestSync(context: Context, name: String) {
        val request = OneTimeWorkRequestBuilder<OutsyncWorker>()
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_NAME to name))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(oneTimeWorkName(name), ExistingWorkPolicy.KEEP, request)
    }

    internal fun engine(name: String): SyncEngine<*>? = engines[name]

    private fun schedulePeriodic(context: Context, name: String, interval: Duration) {
        val request = PeriodicWorkRequestBuilder<OutsyncWorker>(interval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraint())
            .setInputData(workDataOf(KEY_NAME to name))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(periodicWorkName(name), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    internal fun oneTimeWorkName(name: String) = "outsync-$name"
    internal fun periodicWorkName(name: String) = "outsync-periodic-$name"
    private const val TAG = "outsync"
}
