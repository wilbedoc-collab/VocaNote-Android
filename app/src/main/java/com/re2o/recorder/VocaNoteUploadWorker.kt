package com.re2o.recorder

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VocaNoteUploadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val clientId = inputData.getString(KEY_CLIENT_ID)
        if (clientId.isNullOrBlank()) {
            // Upgrade path for the legacy global WorkSpec: dispatch one per-record unique job,
            // but never perform the network upload in the legacy batch work itself.
            enqueueAll(applicationContext)
            return Result.success()
        }
        val uploaded = uploadOne(
            context = applicationContext,
            clientId = clientId,
            shouldContinue = { !isStopped },
            onProgress = { sent, total ->
                setProgressAsync(uploadProgressData(sent, total))
            }
        )
        if (LocalRecordingStore.shouldEnqueueNext(applicationContext)) {
            enqueueNext(applicationContext, clientId)
        }
        return if (uploaded) Result.success() else Result.retry()
    }

    companion object {
        val SERVER_URL: String = BuildConfig.VOCANOTE_SERVER_URL
        val UPLOAD_TOKEN: String = BuildConfig.VOCANOTE_UPLOAD_TOKEN
        private const val KEY_CLIENT_ID = "client_id"
        const val KEY_PROGRESS_SENT_BYTES = "sent_bytes"
        const val KEY_PROGRESS_TOTAL_BYTES = "total_bytes"
        const val KEY_PROGRESS_PERCENT = "percent"

        fun uploadProgressData(sent: Long, total: Long): Data {
            val percent = if (total > 0L) ((sent * 100L) / total).coerceIn(0L, 100L).toInt() else 0
            return Data.Builder()
                .putLong(KEY_PROGRESS_SENT_BYTES, sent)
                .putLong(KEY_PROGRESS_TOTAL_BYTES, total)
                .putInt(KEY_PROGRESS_PERCENT, percent)
                .build()
        }

        fun enqueueAll(context: Context) {
            val next = LocalRecordingStore.recoveryCandidates(context).firstOrNull() ?: return
            enqueue(context, next.clientRecordingId)
        }

        private fun enqueueNext(context: Context, excludingClientId: String) {
            val next = LocalRecordingStore.recoveryCandidates(context)
                .firstOrNull { it.clientRecordingId != excludingClientId } ?: return
            enqueue(context, next.clientRecordingId)
        }

        fun enqueue(context: Context, clientId: String) {
            if (clientId.isBlank()) return
            if (!LocalRecordingStore.canAutomaticallyEnqueue(context, clientId)) return
            enqueueRequest(context, clientId)
        }

        fun enqueueControlledProbe(context: Context, clientId: String) {
            if (clientId.isBlank()) return
            if (!LocalRecordingStore.canEnqueueControlledProbe(context, clientId)) return
            enqueueRequest(context, clientId)
        }

        private fun enqueueRequest(context: Context, clientId: String) {
            val req = OneTimeWorkRequestBuilder<VocaNoteUploadWorker>()
                .setInputData(Data.Builder().putString(KEY_CLIENT_ID, clientId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UploadRecoveryPolicy.workName(clientId), ExistingWorkPolicy.KEEP, req
            )
        }

        fun uploadOne(
            context: Context,
            clientId: String,
            shouldContinue: () -> Boolean = { true },
            onProgress: (Long, Long) -> Unit = { _, _ -> }
        ): Boolean {
            if (!UploadExecutionGate.tryAcquire()) return false
            try {
                val rec = LocalRecordingStore.get(context, clientId) ?: return true
                return when (LocalRecordingStore.recoveryDecision(context, rec)) {
                    UploadRecoveryPolicy.Decision.UPLOAD -> {
                        val claimed = LocalRecordingStore.claimForUpload(context, clientId) ?: return true
                        if (!shouldContinue()) {
                            val pendingStatus = if (claimed.uploadStatus == LocalRecordingStore.STATUS_UPLOAD_AUTH_PROBE_PENDING) {
                                LocalRecordingStore.STATUS_UPLOAD_AUTH_PROBE_PENDING
                            } else LocalRecordingStore.STATUS_UPLOAD_PENDING
                            LocalRecordingStore.updateStatus(context, clientId, pendingStatus)
                            false
                        } else uploadEligible(context, claimed, shouldContinue, onProgress)
                    }
                    UploadRecoveryPolicy.Decision.REPORT_MISSING -> {
                        LocalRecordingStore.updateStatus(context, clientId, LocalRecordingStore.STATUS_UPLOAD_FAILED, error = "local audio missing")
                        true
                    }
                    UploadRecoveryPolicy.Decision.REPORT_CORRUPT_FILE -> {
                        LocalRecordingStore.updateStatus(context, clientId, LocalRecordingStore.STATUS_UPLOAD_FAILED, error = "local audio corrupt")
                        true
                    }
                    UploadRecoveryPolicy.Decision.REPORT_CORRUPT_METADATA -> {
                        LocalRecordingStore.updateStatus(context, clientId, LocalRecordingStore.STATUS_UPLOAD_FAILED, error = "local audio corrupt metadata")
                        true
                    }
                    else -> true
                }
            } finally {
                UploadExecutionGate.release()
            }
        }

        private fun uploadEligible(
            context: Context,
            rec: LocalRecordingStore.LocalRecording,
            shouldContinue: () -> Boolean,
            onProgress: (Long, Long) -> Unit
        ): Boolean {
            val clientId = rec.clientRecordingId
            val isAuthProbe = rec.uploadStatus == LocalRecordingStore.STATUS_UPLOAD_AUTH_PROBE_PENDING
            val file = File(rec.localAudioPath)
            return try {
                val response = multipartUpload(rec, file, shouldContinue, onProgress)
                val root = JSONObject(response)
                val serverId = root.optString("recording_id", root.optString("id", ""))
                val recording = root.optJSONObject("recording")
                val title = recording?.optString("title")?.takeIf { it.isNotBlank() }
                if (serverId.isBlank()) throw IOException("missing recording_id")
                if (isAuthProbe) {
                    LocalRecordingStore.releaseCircuitAfterProbe(
                        context, clientId, LocalRecordingStore.STATUS_PROCESSING,
                        serverId = serverId, serverTitle = title, error = null
                    )
                } else {
                    LocalRecordingStore.updateStatus(
                        context, clientId, LocalRecordingStore.STATUS_PROCESSING,
                        serverId = serverId, serverTitle = title, error = null
                    )
                }
                true
            } catch (e: VocaNoteApiClient.HttpStatusException) {
                when (UploadFailurePolicy.classifyHttp(e.code)) {
                    UploadFailurePolicy.Disposition.TERMINAL -> if (isAuthProbe) {
                        LocalRecordingStore.releaseCircuitAfterProbe(
                            context, clientId, LocalRecordingStore.STATUS_UPLOAD_REJECTED,
                            error = "HTTP ${e.code}", bumpRetry = true
                        )
                    } else LocalRecordingStore.updateStatus(
                        context, clientId, LocalRecordingStore.STATUS_UPLOAD_REJECTED,
                        error = "HTTP ${e.code}", bumpRetry = true
                    )
                    UploadFailurePolicy.Disposition.AUTH_BLOCKED -> if (isAuthProbe) {
                        LocalRecordingStore.keepCircuitAfterProbe(
                            context, clientId, LocalRecordingStore.STATUS_UPLOAD_AUTH_BLOCKED,
                            error = "HTTP ${e.code}"
                        )
                    } else LocalRecordingStore.updateStatus(
                        context, clientId, LocalRecordingStore.STATUS_UPLOAD_AUTH_BLOCKED,
                        error = "HTTP ${e.code}", bumpRetry = true
                    )
                    UploadFailurePolicy.Disposition.RETRY -> {
                        if (isAuthProbe) LocalRecordingStore.keepCircuitAfterProbe(
                            context, clientId, LocalRecordingStore.STATUS_UPLOAD_AUTH_PROBE_PENDING,
                            error = "HTTP ${e.code}"
                        ) else LocalRecordingStore.updateStatus(
                            context, clientId, LocalRecordingStore.STATUS_UPLOAD_PENDING,
                            error = "HTTP ${e.code}", bumpRetry = true
                        )
                        return false
                    }
                }
                true
            } catch (e: Exception) {
                if (isAuthProbe) LocalRecordingStore.keepCircuitAfterProbe(
                    context, clientId, LocalRecordingStore.STATUS_UPLOAD_AUTH_PROBE_PENDING,
                    error = e.javaClass.simpleName
                ) else LocalRecordingStore.updateStatus(
                    context, clientId, LocalRecordingStore.STATUS_UPLOAD_PENDING,
                    error = e.javaClass.simpleName, bumpRetry = true
                )
                false
            }
        }

        private fun multipartUpload(
            rec: LocalRecordingStore.LocalRecording,
            file: File,
            shouldContinue: () -> Boolean,
            onProgress: (Long, Long) -> Unit
        ): String {
            val boundary = "----VocaNote${System.currentTimeMillis()}"
            val conn = URL("$SERVER_URL/api/recordings").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("X-Upload-Token", UPLOAD_TOKEN)
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                val recordedAt = rec.createdAt.ifBlank {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(file.lastModified()))
                }
                val fields = listOf(
                    "title" to (rec.localTitle ?: "무제 녹음"),
                    "meeting_type" to "회의",
                    "recorded_at" to recordedAt,
                    "duration_sec" to (rec.durationMs / 1000L).coerceAtLeast(0L).toString(),
                    "client_recording_id" to rec.clientRecordingId
                )
                StreamingMultipartUpload.configureConnection(
                    conn,
                    StreamingMultipartUpload.contentLength(boundary, fields, file)
                )
                conn.outputStream.use { output ->
                    StreamingMultipartUpload.writeBody(
                        output = output,
                        boundary = boundary,
                        fields = fields,
                        file = file,
                        onProgress = onProgress,
                        shouldContinue = shouldContinue
                    )
                }
                if (!shouldContinue()) throw UploadCancelledException()
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { StreamingMultipartUpload.readResponse(it) }.orEmpty()
                if (code !in 200..299) throw VocaNoteApiClient.HttpStatusException(code, body)
                return body
            } finally {
                conn.disconnect()
            }
        }
    }
}
