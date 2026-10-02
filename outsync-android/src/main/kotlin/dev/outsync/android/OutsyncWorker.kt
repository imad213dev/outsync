package dev.outsync.android

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

public class OutsyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(Outsync.KEY_NAME) ?: return Result.failure()
        val engine = Outsync.engine(name)
        if (engine == null) {
            Log.w(LOG_TAG, "No engine registered for \"$name\". Call Outsync.register() in Application.onCreate().")
            return Result.failure()
        }
        repeat(MAX_ROUNDS) {
            val report = engine.sync()
            if (!report.isSuccess) return Result.retry()
            if (engine.status.value.pendingCount == 0) return Result.success()
        }
        return Result.success()
    }

    private companion object {
        const val LOG_TAG = "Outsync"
        const val MAX_ROUNDS = 3
    }
}
