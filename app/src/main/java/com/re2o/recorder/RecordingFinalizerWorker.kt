package com.re2o.recorder

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/** One durable finalization path for both Activity and notification STOP. */
class RecordingFinalizerWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val clientId = inputData.getString(KEY_CLIENT_ID) ?: return Result.failure()
        if (LocalRecordingStore.isForgotten(applicationContext, clientId)) {
            clearPendingAndNotify(applicationContext, path, clientId)
            return Result.success()
        }
        val createdAt = inputData.getString(KEY_CREATED_AT).orEmpty()
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)
        val file = File(path)
        if (!file.isFile || file.length() <= 2048L) {
            return retryOrRecordTerminalFailure(
                path, clientId, file, createdAt, durationMs,
                if (!file.isFile) "finalization source missing" else "finalization source too short"
            )
        }
        val before = file.length()
        try { Thread.sleep(750L) } catch (_: InterruptedException) { return Result.retry() }
        if (LocalRecordingStore.isForgotten(applicationContext, clientId)) {
            clearPendingAndNotify(applicationContext, path, clientId)
            return Result.success()
        }
        if (!file.isFile || file.length() != before) {
            return retryOrRecordTerminalFailure(
                path, clientId, file, createdAt, durationMs, "finalization source did not stabilize"
            )
        }
        if (!FinalizedAudioValidator.isValid(file)) {
            return retryOrRecordTerminalFailure(
                path, clientId, file, createdAt, durationMs, "finalization source is invalid or unreadable audio"
            )
        }
        val existing = LocalEditStateStore.get(applicationContext, clientId)
        if (existing == null) {
            val registered = LocalRecordingStore.registerSaved(
                applicationContext, clientId, file, createdAt, durationMs, "무제 녹음",
                LocalRecordingStore.STATUS_EDIT_PENDING
            )
            if (!registered) {
                clearPendingAndNotify(applicationContext, path, clientId)
                return Result.success()
            }
            LocalEditStateStore.enterPending(applicationContext, clientId, 1, "STOPPED")
        }
        EditGraceWorker.enqueue(applicationContext, clientId)
        clearPendingAndNotify(applicationContext, path, clientId)
        return Result.success()
    }

    private fun retryOrRecordTerminalFailure(
        path: String,
        clientId: String,
        file: File,
        createdAt: String,
        durationMs: Long,
        reason: String
    ): Result {
        if (RecordingFinalizationPolicy.invalidFileAction(runAttemptCount) ==
            RecordingFinalizationPolicy.InvalidFileAction.RETRY
        ) return Result.retry()
        LocalRecordingStore.registerSaved(
            applicationContext,
            clientId,
            file,
            createdAt,
            durationMs,
            "복구 필요 녹음",
            LocalRecordingStore.STATUS_FINALIZATION_FAILED,
            lastError = reason
        )
        clearPendingAndNotify(applicationContext, path, clientId)
        return Result.success()
    }

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_DURATION_MS = "duration_ms"

        private fun workName(clientId: String): String = "vocanote_finalize_$clientId"

        fun enqueue(context: Context, path: String, clientId: String, createdAt: String, durationMs: Long) {
            val data = Data.Builder().putString(KEY_PATH, path).putString(KEY_CLIENT_ID, clientId)
                .putString(KEY_CREATED_AT, createdAt).putLong(KEY_DURATION_MS, durationMs).build()
            val request = OneTimeWorkRequestBuilder<RecordingFinalizerWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(clientId), ExistingWorkPolicy.KEEP, request
            )
        }

        /** Rebuilds only the exact STOP intent; it never scans production audio files. */
        fun recoverPending(context: Context): Boolean {
            val prefs = context.getSharedPreferences(RecordingService.PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(RecordingService.KEY_FINALIZATION_PENDING, false)) return false
            val path = prefs.getString(RecordingService.KEY_FINALIZATION_PATH, null)
            val clientId = prefs.getString(RecordingService.KEY_FINALIZATION_CLIENT_ID, null)
            if (path.isNullOrBlank() || clientId.isNullOrBlank()) return false
            if (LocalRecordingStore.isForgotten(context, clientId)) {
                clearPendingAndNotify(context, path, clientId)
                return false
            }

            val file = File(path)
            val startedAt = prefs.getLong(
                RecordingService.KEY_FINALIZATION_STARTED_AT_MS,
                file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            val stoppedAt = prefs.getLong(RecordingService.KEY_FINALIZATION_STOPPED_AT_MS, startedAt)
            val createdAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA)
                .format(java.util.Date(startedAt))
            val existing = LocalRecordingStore.get(context, clientId)
            if (existing != null && RecordingFinalizationPolicy.canResumeExisting(
                    path,
                    existing.localAudioPath,
                    file.isFile
                )
            ) {
                if (existing.uploadStatus == LocalRecordingStore.STATUS_EDIT_PENDING &&
                    LocalEditStateStore.get(context, clientId) == null
                ) {
                    LocalEditStateStore.enterPending(context, clientId, 1, "STOPPED")
                }
                EditGraceWorker.enqueue(context, clientId)
                clearPendingAndNotify(context, path, clientId)
                return true
            }
            if (existing != null) {
                LocalRecordingStore.registerSaved(
                    context,
                    clientId,
                    file,
                    createdAt,
                    (stoppedAt - startedAt).coerceAtLeast(0L),
                    "복구 필요 녹음",
                    LocalRecordingStore.STATUS_FINALIZATION_FAILED,
                    lastError = "existing finalization row has no exact source file"
                )
                clearPendingAndNotify(context, path, clientId)
                return true
            }

            when (RecordingFinalizationPolicy.recoveryAction(file.isFile)) {
                RecordingFinalizationPolicy.RecoveryAction.TERMINAL_FAILURE -> {
                    LocalRecordingStore.registerSaved(
                        context,
                        clientId,
                        file,
                        createdAt,
                        (stoppedAt - startedAt).coerceAtLeast(0L),
                        "복구 필요 녹음",
                        LocalRecordingStore.STATUS_FINALIZATION_FAILED,
                        lastError = "finalization source missing during restart recovery"
                    )
                    clearPendingAndNotify(context, path, clientId)
                    return true
                }
                RecordingFinalizationPolicy.RecoveryAction.ENQUEUE_EXACT_PENDING -> Unit
            }
            val pending = RecordingIdentityPolicy.Identity(path, clientId)
            val current = RecordingIdentityPolicy.Identity(
                prefs.getString(RecordingService.KEY_CURRENT_PATH, null),
                prefs.getString(RecordingService.KEY_CLIENT_ID, null)
            )
            when (RecordingIdentityPolicy.recoverPending(
                pending = pending,
                current = current,
                durableIsRecording = prefs.getBoolean(RecordingService.KEY_IS_RECORDING, false)
            )) {
                RecordingIdentityPolicy.Action.STOP_EXACT_CURRENT -> {
                    context.startService(Intent(context, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_STOP
                        putExtra(RecordingService.EXTRA_PATH, path)
                        putExtra(RecordingService.EXTRA_CLIENT_ID, clientId)
                        putExtra(RecordingService.EXTRA_STARTED_AT_MS, startedAt)
                        putExtra(RecordingService.EXTRA_STOPPED_AT_MS, stoppedAt)
                    })
                }
                RecordingIdentityPolicy.Action.ENQUEUE_EXACT_PENDING -> {
                    enqueue(context, path, clientId, createdAt, (stoppedAt - startedAt).coerceAtLeast(0L))
                }
                RecordingIdentityPolicy.Action.IGNORE -> return false
            }
            return true
        }

        private fun clearPendingAndNotify(context: Context, path: String, clientId: String) {
            if (!clearPending(context, path, clientId)) return
            context.sendBroadcast(Intent(RecordingService.ACTION_FINALIZATION_COMPLETED).apply {
                setPackage(context.packageName)
                putExtra(RecordingService.EXTRA_PATH, path)
                putExtra(RecordingService.EXTRA_CLIENT_ID, clientId)
            })
        }

        private fun clearPending(context: Context, path: String, clientId: String): Boolean =
            FinalizationIdentityLock.withLock {
                val prefs = context.getSharedPreferences(RecordingService.PREFS, Context.MODE_PRIVATE)
                val stored = if (prefs.getBoolean(RecordingService.KEY_FINALIZATION_PENDING, false)) {
                    FinalizationIdentityPolicy.Identity(
                        prefs.getString(RecordingService.KEY_FINALIZATION_PATH, null),
                        prefs.getString(RecordingService.KEY_FINALIZATION_CLIENT_ID, null)
                    )
                } else null
                val expected = FinalizationIdentityPolicy.Identity(path, clientId)
                if (!FinalizationIdentityPolicy.canClear(stored, expected)) return@withLock false
                check(
                    prefs.edit()
                        .putBoolean(RecordingService.KEY_FINALIZATION_PENDING, false)
                        .remove(RecordingService.KEY_FINALIZATION_PATH)
                        .remove(RecordingService.KEY_FINALIZATION_CLIENT_ID)
                        .remove(RecordingService.KEY_FINALIZATION_STARTED_AT_MS)
                        .remove(RecordingService.KEY_FINALIZATION_STOPPED_AT_MS)
                        .commit()
                )
                true
            }
    }
}
