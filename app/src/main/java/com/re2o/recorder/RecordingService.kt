package com.re2o.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var levelLoopRunning = false

    companion object {
        const val ACTION_START = "com.re2o.recorder.START"
        const val ACTION_STOP = "com.re2o.recorder.STOP"
        const val ACTION_RECORDING_STARTED = "com.re2o.recorder.RECORDING_STARTED"
        const val ACTION_RECORDING_START_FAILED = "com.re2o.recorder.RECORDING_START_FAILED"
        const val ACTION_QUERY_START_OUTCOME = "com.re2o.recorder.QUERY_START_OUTCOME"
        const val ACTION_RECORDING_STOPPED = "com.re2o.recorder.RECORDING_STOPPED"
        const val ACTION_FINALIZATION_COMPLETED = "com.re2o.recorder.FINALIZATION_COMPLETED"
        const val EXTRA_PATH = "path"
        const val EXTRA_CLIENT_ID = "client_id"
        const val EXTRA_STARTED_AT_MS = "started_at_ms"
        const val EXTRA_STOPPED_AT_MS = "stopped_at_ms"
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 2001
        const val PREFS = "re2o_recorder"
        const val KEY_CURRENT_PATH = "current_path"
        const val KEY_IS_RECORDING = "is_recording"
        const val KEY_CLIENT_ID = "client_recording_id"
        const val KEY_STARTED_AT_MS = "started_at_ms"
        const val KEY_START_FAILED_PATH = "start_failed_path"
        const val KEY_START_FAILED_CLIENT_ID = "start_failed_client_id"
        const val KEY_START_PENDING_PATH = "start_pending_path"
        const val KEY_START_PENDING_CLIENT_ID = "start_pending_client_id"
        const val KEY_FINALIZATION_PENDING = "finalization_pending"
        const val KEY_FINALIZATION_PATH = "finalization_path"
        const val KEY_FINALIZATION_CLIENT_ID = "finalization_client_id"
        const val KEY_FINALIZATION_STARTED_AT_MS = "finalization_started_at_ms"
        const val KEY_FINALIZATION_STOPPED_AT_MS = "finalization_stopped_at_ms"
        const val ACTION_LEVEL = "com.re2o.recorder.LEVEL"
        const val EXTRA_AMPLITUDE = "amplitude"

        fun startFailurePathKey(clientId: String): String = "start_failed_path_$clientId"

        fun persistFinalizationIntent(
            context: Context,
            path: String,
            clientId: String,
            startedAt: Long,
            stoppedAt: Long
        ): Boolean = FinalizationIdentityLock.withLock {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val requested = FinalizationIdentityPolicy.Identity(path, clientId)
            val storedPending = if (prefs.getBoolean(KEY_FINALIZATION_PENDING, false)) {
                FinalizationIdentityPolicy.Identity(
                    prefs.getString(KEY_FINALIZATION_PATH, null),
                    prefs.getString(KEY_FINALIZATION_CLIENT_ID, null)
                )
            } else null
            val current = FinalizationIdentityPolicy.Identity(
                prefs.getString(KEY_CURRENT_PATH, null),
                prefs.getString(KEY_CLIENT_ID, null)
            )
            if (!FinalizationIdentityPolicy.canPublish(
                    storedPending,
                    requested,
                    current,
                    prefs.getBoolean(KEY_IS_RECORDING, false)
                )
            ) return@withLock false
            prefs.edit()
                .putBoolean(KEY_FINALIZATION_PENDING, true)
                .putString(KEY_FINALIZATION_PATH, path)
                .putString(KEY_FINALIZATION_CLIENT_ID, clientId)
                .putLong(KEY_FINALIZATION_STARTED_AT_MS, startedAt)
                .putLong(KEY_FINALIZATION_STOPPED_AT_MS, stoppedAt)
                .commit()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            recoverInterruptedRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val path = intent.getStringExtra(EXTRA_PATH)?.takeIf { it.isNotBlank() }
                    ?: return START_NOT_STICKY
                val clientId = intent.getStringExtra(EXTRA_CLIENT_ID)?.takeIf { it.isNotBlank() }
                    ?: return START_NOT_STICKY
                val requested = RecordingIdentityPolicy.Identity(path, clientId)
                val durablePending = RecordingIdentityPolicy.Identity(
                    prefs.getString(KEY_START_PENDING_PATH, null),
                    prefs.getString(KEY_START_PENDING_CLIENT_ID, null)
                )
                if (!RecordingIdentityPolicy.canStart(
                        requested = requested,
                        durablePending = durablePending,
                        durableIsRecording = prefs.getBoolean(KEY_IS_RECORDING, false),
                        finalizationPending = prefs.getBoolean(KEY_FINALIZATION_PENDING, false),
                        recorderAlreadyActive = recorder != null
                    )
                ) {
                    publishStartFailure(
                        path,
                        clientId,
                        clearPendingIfExact = requested == durablePending,
                        markInactive = false
                    )
                    return START_STICKY
                }
                val startedAt = intent.getLongExtra(EXTRA_STARTED_AT_MS, System.currentTimeMillis())
                startForeground(NOTIFICATION_ID, notification("녹음 중", "화면이 꺼져도 녹음이 계속됩니다."))
                if (!startRecording(path)) {
                    publishStartFailure(path, clientId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val published = RecordingStartIdentityLock.withLock {
                    val latestPending = RecordingIdentityPolicy.Identity(
                        prefs.getString(KEY_START_PENDING_PATH, null),
                        prefs.getString(KEY_START_PENDING_CLIENT_ID, null)
                    )
                    if (latestPending != requested) return@withLock false
                    val editor = prefs.edit().putString(KEY_CURRENT_PATH, path)
                        .putString(KEY_CLIENT_ID, clientId).putLong(KEY_STARTED_AT_MS, startedAt)
                        .putBoolean(KEY_IS_RECORDING, true)
                        .remove(KEY_START_PENDING_PATH).remove(KEY_START_PENDING_CLIENT_ID)
                        .remove(startFailurePathKey(clientId))
                    val failed = RecordingIdentityPolicy.Identity(
                        prefs.getString(KEY_START_FAILED_PATH, null),
                        prefs.getString(KEY_START_FAILED_CLIENT_ID, null)
                    )
                    if (failed == requested) {
                        editor.remove(KEY_START_FAILED_PATH).remove(KEY_START_FAILED_CLIENT_ID)
                    }
                    editor.commit()
                }
                if (!published) {
                    stopRecording()
                    publishStartFailure(path, clientId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                sendBroadcast(Intent(ACTION_RECORDING_STARTED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PATH, path)
                    putExtra(EXTRA_CLIENT_ID, clientId)
                })
            }
            ACTION_QUERY_START_OUTCOME -> {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val durablePending = RecordingIdentityPolicy.Identity(
                    prefs.getString(KEY_START_PENDING_PATH, null),
                    prefs.getString(KEY_START_PENDING_CLIENT_ID, null)
                )
                val requested = RecordingIdentityPolicy.Identity(
                    intent.getStringExtra(EXTRA_PATH),
                    intent.getStringExtra(EXTRA_CLIENT_ID)
                ).takeIf { it.isComplete() } ?: durablePending
                if (!requested.isComplete()) return START_NOT_STICKY
                val current = RecordingIdentityPolicy.Identity(
                    prefs.getString(KEY_CURRENT_PATH, null),
                    prefs.getString(KEY_CLIENT_ID, null)
                )
                val failed = RecordingIdentityPolicy.Identity(
                    prefs.getString(startFailurePathKey(requested.clientId!!), null),
                    requested.clientId
                )
                when (RecordingIdentityPolicy.startOutcome(
                    requested = requested,
                    durableCurrent = current,
                    durableIsRecording = prefs.getBoolean(KEY_IS_RECORDING, false),
                    failed = failed
                )) {
                    RecordingIdentityPolicy.StartOutcome.STARTED -> sendBroadcast(Intent(ACTION_RECORDING_STARTED).apply {
                        setPackage(packageName); putExtra(EXTRA_PATH, requested.path); putExtra(EXTRA_CLIENT_ID, requested.clientId)
                    })
                    RecordingIdentityPolicy.StartOutcome.FAILED -> publishStartFailure(
                        requested.path!!,
                        requested.clientId!!,
                        clearPendingIfExact = requested == durablePending,
                        markInactive = false
                    )
                    RecordingIdentityPolicy.StartOutcome.WAIT -> {
                        if (recorder == null && !prefs.getBoolean(KEY_IS_RECORDING, false)) {
                            publishStartFailure(
                                requested.path!!,
                                requested.clientId!!,
                                clearPendingIfExact = requested == durablePending,
                                markInactive = false
                            )
                        }
                    }
                }
            }
            ACTION_STOP -> {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val current = RecordingIdentityPolicy.Identity(
                    prefs.getString(KEY_CURRENT_PATH, null),
                    prefs.getString(KEY_CLIENT_ID, null)
                )
                val pending = if (prefs.getBoolean(KEY_FINALIZATION_PENDING, false)) {
                    RecordingIdentityPolicy.Identity(
                        prefs.getString(KEY_FINALIZATION_PATH, null),
                        prefs.getString(KEY_FINALIZATION_CLIENT_ID, null)
                    )
                } else null
                val hasRequestedIdentity = intent.hasExtra(EXTRA_PATH) || intent.hasExtra(EXTRA_CLIENT_ID)
                val requested = if (hasRequestedIdentity) {
                    RecordingIdentityPolicy.Identity(
                        intent.getStringExtra(EXTRA_PATH),
                        intent.getStringExtra(EXTRA_CLIENT_ID)
                    )
                } else null
                when (RecordingIdentityPolicy.handleStop(
                    requested = requested,
                    pending = pending,
                    current = current,
                    durableIsRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
                )) {
                    RecordingIdentityPolicy.Action.STOP_EXACT_CURRENT -> {
                        val path = current.path!!
                        val clientId = current.clientId!!
                        val matchesPending = pending == current
                        val startedAt = when {
                            intent.hasExtra(EXTRA_STARTED_AT_MS) -> intent.getLongExtra(EXTRA_STARTED_AT_MS, 0L)
                            matchesPending -> prefs.getLong(KEY_FINALIZATION_STARTED_AT_MS, 0L)
                            else -> prefs.getLong(KEY_STARTED_AT_MS, 0L)
                        }
                        val stoppedAt = when {
                            intent.hasExtra(EXTRA_STOPPED_AT_MS) -> intent.getLongExtra(EXTRA_STOPPED_AT_MS, 0L)
                            matchesPending -> prefs.getLong(KEY_FINALIZATION_STOPPED_AT_MS, 0L)
                            else -> System.currentTimeMillis()
                        }
                        if (startedAt <= 0L || stoppedAt < startedAt) return START_STICKY
                        if (!persistFinalizationIntent(this, path, clientId, startedAt, stoppedAt)) return START_STICKY
                        stopRecording()
                        enqueueFinalizer(path, clientId, startedAt, stoppedAt)
                        check(prefs.edit().putString(KEY_CLIENT_ID, clientId).putBoolean(KEY_IS_RECORDING, false).commit())
                        sendBroadcast(Intent(ACTION_RECORDING_STOPPED).apply { setPackage(packageName) })
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    RecordingIdentityPolicy.Action.ENQUEUE_EXACT_PENDING -> {
                        val exactPending = pending ?: return START_STICKY
                        val startedAt = prefs.getLong(KEY_FINALIZATION_STARTED_AT_MS, 0L)
                        val stoppedAt = prefs.getLong(KEY_FINALIZATION_STOPPED_AT_MS, startedAt)
                        if (startedAt <= 0L || stoppedAt < startedAt) return START_STICKY
                        enqueueFinalizer(exactPending.path!!, exactPending.clientId!!, startedAt, stoppedAt)
                    }
                    RecordingIdentityPolicy.Action.IGNORE -> return START_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun publishStartFailure(
        path: String,
        clientId: String,
        clearPendingIfExact: Boolean = true,
        markInactive: Boolean = true
    ) {
        RecordingStartIdentityLock.withLock {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val editor = prefs.edit()
                .putString(KEY_START_FAILED_PATH, path)
                .putString(KEY_START_FAILED_CLIENT_ID, clientId)
                .putString(startFailurePathKey(clientId), path)
            if (markInactive) editor.putBoolean(KEY_IS_RECORDING, false)
            val durablePending = RecordingIdentityPolicy.Identity(
                prefs.getString(KEY_START_PENDING_PATH, null),
                prefs.getString(KEY_START_PENDING_CLIENT_ID, null)
            )
            if (clearPendingIfExact && durablePending == RecordingIdentityPolicy.Identity(path, clientId)) {
                editor.remove(KEY_START_PENDING_PATH).remove(KEY_START_PENDING_CLIENT_ID)
            }
            editor.commit()
        }
        sendBroadcast(Intent(ACTION_RECORDING_START_FAILED).apply {
            setPackage(packageName); putExtra(EXTRA_PATH, path); putExtra(EXTRA_CLIENT_ID, clientId)
        })
    }

    private fun recoverInterruptedRecording(): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_IS_RECORDING, false)) return false
        val path = prefs.getString(KEY_CURRENT_PATH, null)?.takeIf { it.isNotBlank() } ?: return false
        val clientId = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: return false
        val startedAt = prefs.getLong(KEY_STARTED_AT_MS, 0L)
        val stoppedAt = System.currentTimeMillis()
        if (startedAt <= 0L || stoppedAt < startedAt) return false
        if (!persistFinalizationIntent(this, path, clientId, startedAt, stoppedAt)) return false
        stopRecording()
        enqueueFinalizer(path, clientId, startedAt, stoppedAt)
        check(prefs.edit().putBoolean(KEY_IS_RECORDING, false).commit())
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED).apply { setPackage(packageName) })
        return true
    }

    private fun enqueueFinalizer(path: String, clientId: String, startedAt: Long, stoppedAt: Long) {
        val createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(startedAt))
        RecordingFinalizerWorker.enqueue(
            this,
            path,
            clientId,
            createdAt,
            (stoppedAt - startedAt).coerceAtLeast(0L)
        )
    }

    private fun startRecording(path: String): Boolean {
        if (recorder != null) return false
        File(path).parentFile?.mkdirs()
        val candidate = MediaRecorder()
        return try {
            candidate.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96000)
                setAudioSamplingRate(44100)
                setOutputFile(path)
                prepare()
                start()
            }
            recorder = candidate
            startLevelLoop()
            true
        } catch (_: Exception) {
            try { candidate.release() } catch (_: Exception) {}
            false
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        try { r.stop() } catch (_: Exception) {}
        r.release()
        recorder = null
        levelLoopRunning = false
    }

    private fun startLevelLoop() {
        if (levelLoopRunning) return
        levelLoopRunning = true
        handler.post(object : Runnable {
            override fun run() {
                if (!levelLoopRunning) return
                val amp = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                sendBroadcast(Intent(ACTION_LEVEL).apply { setPackage(packageName); putExtra(EXTRA_AMPLITUDE, amp) })
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "녹음", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun notification(title: String, text: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 7, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 8, openIntent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "종료", stopPending)
            .build()
    }

    override fun onDestroy() {
        recoverInterruptedRecording()
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
