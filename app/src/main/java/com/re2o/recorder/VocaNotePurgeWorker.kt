package com.re2o.recorder

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Idempotent, persisted Android-side purge convergence worker. */
class VocaNotePurgeWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        return try {
            val commands = VocaNoteApiClient.pendingAndroidPurges(SERVER_URL, UPLOAD_TOKEN)
            for (command in commands) {
                if (!PurgeDeletionPolicy.isValidGeneration(command.generation)) return Result.retry()
                if (!LocalAudioReferenceStore.applyPurge(applicationContext, command)) return Result.retry()
                if (!VocaNoteApiClient.acknowledgeAndroidPurge(SERVER_URL, UPLOAD_TOKEN, command.purgeId)) return Result.retry()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private val SERVER_URL: String = BuildConfig.VOCANOTE_SERVER_URL
        private val UPLOAD_TOKEN: String = BuildConfig.VOCANOTE_UPLOAD_TOKEN

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<VocaNotePurgeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("vocanote_pending_purges", ExistingWorkPolicy.KEEP, request)
        }

        fun schedule(context: Context) {
            enqueue(context)
            val periodic = PeriodicWorkRequestBuilder<VocaNotePurgeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                "vocanote_pending_purges_periodic", ExistingPeriodicWorkPolicy.KEEP, periodic
            )
        }
    }
}
