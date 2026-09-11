package com.re2o.recorder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Confirms only an untouched expired EDIT_PENDING row; active/expired edits never upload. */
class EditGraceWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val clientId = inputData.getString(KEY_CLIENT_ID) ?: return Result.failure()
        if (LocalRecordingStore.isForgotten(applicationContext, clientId)) return Result.success()
        return when (LocalEditStateStore.evaluateDeadline(applicationContext, clientId)) {
            "AUTO_CONFIRMED", "REGISTER_UPLOAD" -> {
                LocalRecordingStore.updateStatus(applicationContext, clientId, LocalRecordingStore.STATUS_UPLOAD_PENDING)
                // UPLOAD_PENDING is the durable handoff; startup recovery can enqueue it
                // even if this worker dies immediately after removing edit state.
                LocalEditStateStore.remove(applicationContext, clientId)
                VocaNoteUploadWorker.enqueue(applicationContext, clientId)
                Result.success()
            }
            "NO_ACTION" -> {
                val state = LocalEditStateStore.get(applicationContext, clientId)
                if (state?.state == LocalEditStateStore.EDIT_PENDING) {
                    enqueue(applicationContext, clientId)
                }
                Result.success()
            }
            "BLOCKED", "RECOVERY_REQUIRED" -> Result.success()
            else -> Result.failure()
        }
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"

        fun enqueue(context: Context, clientId: String) {
            val state = LocalEditStateStore.get(context, clientId) ?: return
            val delay = (state.graceDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<EditGraceWorker>()
                .setInputData(Data.Builder().putString(KEY_CLIENT_ID, clientId).build())
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "vocanote_edit_grace_$clientId", ExistingWorkPolicy.REPLACE, request
            )
        }

        fun enqueueAllPending(context: Context) {
            LocalEditStateStore.pending(context).forEach { enqueue(context, it.recordingId) }
        }
    }
}
