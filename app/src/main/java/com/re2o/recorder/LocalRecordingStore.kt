package com.re2o.recorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LocalRecordingStore {
    const val STATUS_LOCAL_RECORDING = "LOCAL_RECORDING"
    const val STATUS_LOCAL_SAVED = "LOCAL_SAVED"
    const val STATUS_EDIT_PENDING = "EDIT_PENDING"
    const val STATUS_UPLOAD_PENDING = "UPLOAD_PENDING"
    const val STATUS_UPLOADING = "UPLOADING"
    const val STATUS_SERVER_ACCEPTED = "SERVER_ACCEPTED"
    const val STATUS_PROCESSING = "PROCESSING"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_UPLOAD_FAILED = "UPLOAD_FAILED"
    const val STATUS_UPLOAD_REJECTED = "UPLOAD_REJECTED"
    const val STATUS_UPLOAD_AUTH_BLOCKED = "UPLOAD_AUTH_BLOCKED"
    const val STATUS_UPLOAD_AUTH_PROBE_PENDING = "UPLOAD_AUTH_PROBE_PENDING"
    const val STATUS_FINALIZATION_FAILED = "FINALIZATION_FAILED"

    private const val PREFS = "vocanote_local_recordings"
    private const val KEY_ITEMS = "items"
    private const val KEY_FORGOTTEN = "forgotten_client_ids"
    @Volatile private var processStartAuthRecoveryAttempted = false

    fun markForgotten(context: Context, clientId: String): Boolean =
        DeletionPurgeOperationLock.withLock {
            synchronized(this) {
                val current = get(context, clientId)
                if (current != null && UploadLifecyclePolicy.forgetDecision(
                        current.uploadStatus,
                        current.serverRecordingId
                    ) != UploadLifecyclePolicy.ForgetDecision.DELETE_LOCALLY
                ) return@synchronized false
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val ids = prefs.getStringSet(KEY_FORGOTTEN, emptySet())?.toMutableSet() ?: mutableSetOf()
                ids.add(clientId)
                check(prefs.edit().putStringSet(KEY_FORGOTTEN, ids).commit())
                true
            }
        }

    @Synchronized
    fun isForgotten(context: Context, clientId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FORGOTTEN, emptySet())?.contains(clientId) == true

    data class LocalRecording(
        val clientRecordingId: String,
        val localAudioPath: String,
        val createdAt: String,
        val durationMs: Long,
        val uploadStatus: String,
        val serverRecordingId: String?,
        val retryCount: Int,
        val lastError: String?,
        val createdTimestamp: Long,
        val updatedTimestamp: Long,
        val localTitle: String?,
        val serverTitle: String?
    )

    @Synchronized
    fun load(context: Context): MutableList<LocalRecording> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<LocalRecording>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(fromJson(o))
        }
        return out
    }

    @Synchronized
    fun save(context: Context, items: List<LocalRecording>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, arr.toString())
                .commit()
        )
    }

    @Synchronized
    fun get(context: Context, clientId: String): LocalRecording? = load(context).firstOrNull { it.clientRecordingId == clientId }

    @Synchronized
    fun upsert(context: Context, rec: LocalRecording) {
        val items = load(context)
        val idx = items.indexOfFirst { it.clientRecordingId == rec.clientRecordingId }
        if (idx >= 0) items[idx] = rec.copy(updatedTimestamp = System.currentTimeMillis()) else items.add(rec)
        save(context, items)
    }

    fun updateStatus(context: Context, clientId: String, status: String, serverId: String? = null, serverTitle: String? = null, error: String? = null, bumpRetry: Boolean = false) {
        val localPath = synchronized(this) {
            val cur = get(context, clientId) ?: return
            upsert(context, cur.copy(
                uploadStatus = status,
                serverRecordingId = serverId ?: cur.serverRecordingId,
                serverTitle = serverTitle ?: cur.serverTitle,
                lastError = error,
                retryCount = cur.retryCount + if (bumpRetry) 1 else 0
            ))
            cur.localAudioPath
        }
        // Never hold the recording-store monitor while entering the operation/reference locks.
        if (!serverId.isNullOrBlank() && localPath.isNotBlank()) {
            LocalAudioReferenceStore.register(context, serverId, 1, localPath)
        }
    }

    fun registerSaved(context: Context, clientId: String, file: File, createdAt: String, durationMs: Long, title: String = "무제 녹음", status: String = STATUS_UPLOAD_PENDING, lastError: String? = null): Boolean =
        DeletionPurgeOperationLock.withLock {
            synchronized(this) {
                if (!RecordingPersistencePolicy.canRegisterSaved(isForgotten(context, clientId))) {
                    return@synchronized false
                }
                val now = System.currentTimeMillis()
                val existing = get(context, clientId)
                val rec = LocalRecording(
                    clientRecordingId = clientId,
                    localAudioPath = file.absolutePath,
                    createdAt = createdAt,
                    durationMs = durationMs,
                    uploadStatus = status,
                    serverRecordingId = existing?.serverRecordingId,
                    retryCount = existing?.retryCount ?: 0,
                    lastError = lastError ?: existing?.lastError,
                    createdTimestamp = existing?.createdTimestamp ?: now,
                    updatedTimestamp = now,
                    localTitle = title,
                    serverTitle = existing?.serverTitle
                )
                upsert(context, rec)
                true
            }
        }

    @Synchronized
    fun recoveryDecision(context: Context, recording: LocalRecording): UploadRecoveryPolicy.Decision {
        val file = File(recording.localAudioPath)
        return UploadRecoveryPolicy.decision(
            UploadRecoveryPolicy.Candidate(
                clientRecordingId = recording.clientRecordingId,
                status = recording.uploadStatus,
                serverRecordingId = recording.serverRecordingId,
                forgotten = isForgotten(context, recording.clientRecordingId),
                editState = LocalEditStateStore.get(context, recording.clientRecordingId)?.state,
                localPath = recording.localAudioPath,
                fileExists = file.exists(),
                fileSize = if (file.exists()) file.length() else 0L,
                fileValid = LocalAudioFileValidator.isRecognizedAudio(file)
            )
        )
    }

    data class LocalForgetResult(
        val decision: UploadLifecyclePolicy.ForgetDecision,
        val localAudioPath: String?,
        val removed: Boolean,
        val serverRecordingId: String? = null
    )

    /** Deletes audio before atomically committing forget state, fenced from finalizer registration. */
    fun tryForgetLocal(context: Context, visibleId: String): LocalForgetResult =
        DeletionPurgeOperationLock.withLock {
            synchronized(this) {
                val clientId = visibleId.removePrefix("local:")
                val items = load(context)
                val recording = items.firstOrNull {
                    it.clientRecordingId == clientId || it.serverRecordingId == visibleId
                } ?: return@synchronized LocalForgetResult(UploadLifecyclePolicy.ForgetDecision.DELETE_LOCALLY, null, false)
                val decision = UploadLifecyclePolicy.forgetDecision(recording.uploadStatus, recording.serverRecordingId)
                if (decision != UploadLifecyclePolicy.ForgetDecision.DELETE_LOCALLY) {
                    return@synchronized LocalForgetResult(decision, recording.localAudioPath, false, recording.serverRecordingId)
                }

                val file = File(recording.localAudioPath)
                val removed = LocalAudioDeletionVerifier.deleteThenCommit(
                    exists = file::exists,
                    delete = file::delete
                ) {
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val forgotten = prefs.getStringSet(KEY_FORGOTTEN, emptySet())?.toMutableSet() ?: mutableSetOf()
                    forgotten.add(recording.clientRecordingId)
                    val kept = items.filterNot { it.clientRecordingId == recording.clientRecordingId }
                    val arr = JSONArray().apply { kept.forEach { put(toJson(it)) } }
                    check(prefs.edit().putStringSet(KEY_FORGOTTEN, forgotten).putString(KEY_ITEMS, arr.toString()).commit())
                }
                LocalForgetResult(decision, recording.localAudioPath, removed)
            }
        }

    /** Checks forgotten/state and commits UPLOADING while holding the same monitor. */
    @Synchronized
    fun claimForUpload(context: Context, clientId: String): LocalRecording? {
        val recording = get(context, clientId) ?: return null
        val statuses = load(context).map { it.uploadStatus }
        if (!UploadAuthCircuitPolicy.canClaim(recording.uploadStatus, statuses)) return null
        if (recoveryDecision(context, recording) != UploadRecoveryPolicy.Decision.UPLOAD) return null
        val claimed = recording.copy(
            // A probe keeps its durable lease until its HTTP outcome is committed.
            uploadStatus = if (recording.uploadStatus == STATUS_UPLOAD_AUTH_PROBE_PENDING) {
                STATUS_UPLOAD_AUTH_PROBE_PENDING
            } else STATUS_UPLOADING,
            lastError = null,
            updatedTimestamp = System.currentTimeMillis()
        )
        val items = load(context)
        val index = items.indexOfFirst { it.clientRecordingId == clientId }
        if (index < 0) return null
        items[index] = claimed
        save(context, items)
        return claimed
    }

    /** Returns only safe upload candidates and durably reports invalid local inputs. */
    @Synchronized
    fun recoveryCandidates(context: Context): List<LocalRecording> {
        if (UploadAuthCircuitPolicy.isActive(load(context).map { it.uploadStatus })) return emptyList()
        val eligible = mutableListOf<LocalRecording>()
        load(context).sortedWith(
            compareBy<LocalRecording> { UploadRecoveryPolicy.recoveryPriority(it.uploadStatus) }
                .thenBy { it.updatedTimestamp }
        ).forEach { recording ->
            when (recoveryDecision(context, recording)) {
                UploadRecoveryPolicy.Decision.UPLOAD -> eligible.add(recording)
                UploadRecoveryPolicy.Decision.REPORT_MISSING -> {
                    if (recording.lastError != "local audio missing") {
                        updateStatus(context, recording.clientRecordingId, STATUS_UPLOAD_FAILED, error = "local audio missing")
                    }
                }
                UploadRecoveryPolicy.Decision.REPORT_CORRUPT_FILE -> {
                    if (recording.lastError != "local audio corrupt") {
                        updateStatus(context, recording.clientRecordingId, STATUS_UPLOAD_FAILED, error = "local audio corrupt")
                    }
                }
                UploadRecoveryPolicy.Decision.REPORT_CORRUPT_METADATA -> {
                    if (recording.clientRecordingId.isNotBlank() && recording.lastError != "local audio corrupt metadata") {
                        updateStatus(context, recording.clientRecordingId, STATUS_UPLOAD_FAILED, error = "local audio corrupt metadata")
                    }
                }
                else -> Unit
            }
        }
        return eligible
    }

    @Synchronized
    fun pending(context: Context): List<LocalRecording> = recoveryCandidates(context)

    fun enqueuePendingUploads(context: Context) {
        VocaNoteUploadWorker.enqueueAll(context)
    }

    @Synchronized
    fun isAuthCircuitActive(context: Context): Boolean =
        UploadAuthCircuitPolicy.isActive(load(context).map { it.uploadStatus })

    @Synchronized
    fun canAutomaticallyEnqueue(context: Context, clientId: String): Boolean {
        val items = load(context)
        val recording = items.firstOrNull { it.clientRecordingId == clientId } ?: return false
        return UploadAuthCircuitPolicy.canAutomaticallyEnqueue(
            recording.uploadStatus,
            items.map { it.uploadStatus }
        ) && recoveryDecision(context, recording) == UploadRecoveryPolicy.Decision.UPLOAD
    }

    @Synchronized
    fun canEnqueueControlledProbe(context: Context, clientId: String): Boolean {
        val items = load(context)
        val recording = items.firstOrNull { it.clientRecordingId == clientId } ?: return false
        return recording.uploadStatus == STATUS_UPLOAD_AUTH_PROBE_PENDING &&
            UploadAuthCircuitPolicy.canClaim(recording.uploadStatus, items.map { it.uploadStatus }) &&
            recoveryDecision(context, recording) == UploadRecoveryPolicy.Decision.UPLOAD
    }

    @Synchronized
    fun shouldEnqueueNext(context: Context): Boolean =
        UploadAuthCircuitPolicy.shouldEnqueueNext(load(context).map { it.uploadStatus })

    /** At most one auth-blocked row is durably leased as a probe per process start. */
    fun recoverOneAuthBlockedForProcessStart(context: Context): String? {
        val clientId = synchronized(this) {
            if (processStartAuthRecoveryAttempted) return null
            processStartAuthRecoveryAttempted = true
            prepareControlledAuthProbe(context, UploadRecoveryPolicy.RecoveryTrigger.PROCESS_START)
        } ?: return null
        VocaNoteUploadWorker.enqueueControlledProbe(context, clientId)
        return clientId
    }

    fun requestManualUploadRetry(context: Context, clientId: String): Boolean {
        val probeId = synchronized(this) {
            if (isAuthCircuitActive(context)) {
                prepareControlledAuthProbe(
                    context,
                    UploadRecoveryPolicy.RecoveryTrigger.MANUAL,
                    preferredClientId = clientId
                )
            } else null
        }
        if (probeId != null) {
            VocaNoteUploadWorker.enqueueControlledProbe(context, probeId)
            return true
        }
        val recording = get(context, clientId) ?: return false
        if (recoveryDecision(context, recording) != UploadRecoveryPolicy.Decision.UPLOAD) return false
        VocaNoteUploadWorker.enqueue(context, clientId)
        return true
    }

    /** Reuses an existing durable probe; otherwise promotes exactly one blocked row. */
    @Synchronized
    private fun prepareControlledAuthProbe(
        context: Context,
        trigger: UploadRecoveryPolicy.RecoveryTrigger,
        preferredClientId: String? = null
    ): String? {
        val items = load(context).sortedBy { it.updatedTimestamp }.toMutableList()
        val initialCandidates = items.map { it.toRecoveryCandidate(context) }
        val normalization = UploadRecoveryPolicy.normalizeAuthProbes(initialCandidates)
        normalization.retirements.forEach { retirement ->
            val current = items[retirement.candidateIndex]
            items[retirement.candidateIndex] = current.copy(
                uploadStatus = retirement.status,
                lastError = retirement.error,
                updatedTimestamp = System.currentTimeMillis()
            )
        }
        val candidates = items.map { it.toRecoveryCandidate(context) }
        val existingProbe = normalization.retainedProbeIndex?.let { candidates[it] }
        val preferredBlocked = if (trigger == UploadRecoveryPolicy.RecoveryTrigger.MANUAL) {
            candidates.firstOrNull {
                it.clientRecordingId == preferredClientId &&
                    it.status == STATUS_UPLOAD_AUTH_BLOCKED &&
                    UploadRecoveryPolicy.decision(it.copy(status = STATUS_UPLOAD_PENDING)) ==
                        UploadRecoveryPolicy.Decision.UPLOAD
            }
        } else null
        val selected = existingProbe ?: preferredBlocked ?:
            UploadRecoveryPolicy.selectControlledRetry(candidates, trigger)
        if (selected == null) {
            if (normalization.retirements.isNotEmpty()) save(context, items)
            return null
        }
        if (selected.status == STATUS_UPLOAD_AUTH_PROBE_PENDING) {
            if (normalization.retirements.isNotEmpty()) save(context, items)
            check(items.count { it.uploadStatus == STATUS_UPLOAD_AUTH_PROBE_PENDING } == 1)
            return selected.clientRecordingId
        }
        val index = items.indexOfFirst { it.clientRecordingId == selected.clientRecordingId }
        if (index < 0) return null
        items[index] = items[index].copy(
            uploadStatus = STATUS_UPLOAD_AUTH_PROBE_PENDING,
            lastError = null,
            updatedTimestamp = System.currentTimeMillis()
        )
        check(items.count { it.uploadStatus == STATUS_UPLOAD_AUTH_PROBE_PENDING } == 1)
        save(context, items)
        return selected.clientRecordingId
    }

    /** An authenticated success/permanent response opens the rest of the queue atomically. */
    fun releaseCircuitAfterProbe(
        context: Context,
        clientId: String,
        probeStatus: String,
        serverId: String? = null,
        serverTitle: String? = null,
        error: String? = null,
        bumpRetry: Boolean = false
    ) {
        val localPath = synchronized(this) {
            val items = load(context)
            val index = items.indexOfFirst { it.clientRecordingId == clientId }
            if (index < 0 || items[index].uploadStatus != STATUS_UPLOAD_AUTH_PROBE_PENDING) return
            val current = items[index]
            items[index] = current.copy(
                uploadStatus = probeStatus,
                serverRecordingId = serverId ?: current.serverRecordingId,
                serverTitle = serverTitle ?: current.serverTitle,
                lastError = error,
                retryCount = current.retryCount + if (bumpRetry) 1 else 0,
                updatedTimestamp = System.currentTimeMillis()
            )
            items.indices.forEach { itemIndex ->
                if (items[itemIndex].uploadStatus == STATUS_UPLOAD_AUTH_BLOCKED) {
                    items[itemIndex] = items[itemIndex].copy(
                        uploadStatus = STATUS_UPLOAD_PENDING,
                        lastError = null,
                        updatedTimestamp = System.currentTimeMillis()
                    )
                }
            }
            save(context, items)
            current.localAudioPath
        }
        if (!serverId.isNullOrBlank() && localPath.isNotBlank()) {
            LocalAudioReferenceStore.register(context, serverId, 1, localPath)
        }
    }

    /** Auth/transient probe outcomes retain a durable closed-circuit state. */
    fun keepCircuitAfterProbe(
        context: Context,
        clientId: String,
        probeStatus: String,
        error: String,
        bumpRetry: Boolean = true
    ) {
        updateStatus(context, clientId, probeStatus, error = error, bumpRetry = bumpRetry)
    }

    private fun LocalRecording.toRecoveryCandidate(context: Context): UploadRecoveryPolicy.Candidate {
        val file = File(localAudioPath)
        return UploadRecoveryPolicy.Candidate(
            clientRecordingId = clientRecordingId,
            status = uploadStatus,
            serverRecordingId = serverRecordingId,
            forgotten = isForgotten(context, clientRecordingId),
            editState = LocalEditStateStore.get(context, clientRecordingId)?.state,
            localPath = localAudioPath,
            fileExists = file.exists(),
            fileSize = if (file.exists()) file.length() else 0L,
            fileValid = LocalAudioFileValidator.isRecognizedAudio(file)
        )
    }

    @Synchronized
    fun removeByVisibleId(context: Context, visibleId: String): Boolean {
        val items = load(context)
        val before = items.size
        val client = visibleId.removePrefix("local:")
        val matching = items.firstOrNull { it.clientRecordingId == client || it.serverRecordingId == visibleId }
        if (matching != null && UploadLifecyclePolicy.forgetDecision(
                matching.uploadStatus,
                matching.serverRecordingId
            ) != UploadLifecyclePolicy.ForgetDecision.DELETE_LOCALLY
        ) return false
        val kept = items.filterNot { it.clientRecordingId == client || it.serverRecordingId == visibleId }
        if (kept.size != before) {
            save(context, kept)
            return true
        }
        return false
    }

    /** Server delete is not complete until exact local audio is absent and both stores commit. */
    fun cleanupAfterServerDelete(context: Context, serverId: String): Boolean =
        DeletionPurgeOperationLock.withLock {
            // Each store call completes before entering the other store monitor.
            val discoveredItems = load(context)
            val discoveredMatching = discoveredItems.filter { it.serverRecordingId == serverId }
            val referencedPaths = LocalAudioReferenceStore.pathsForServerId(context, serverId)
            val paths = (discoveredMatching.map { it.localAudioPath } + referencedPaths)
                .filter { it.isNotBlank() }
                .distinct()
            if (paths.any { path ->
                    discoveredItems.any { it.serverRecordingId != serverId && it.localAudioPath == path } ||
                        LocalAudioReferenceStore.hasOtherOwner(context, serverId, path)
                }
            ) return@withLock false

            LocalAudioDeletionVerifier.deleteAllThenCommit(
                paths = paths,
                exists = { path -> File(path).exists() },
                delete = { path -> File(path).delete() }
            ) {
                check(LocalAudioReferenceStore.removeForServerId(context, serverId))
                synchronized(this) {
                    // Reload so an unrelated recording update is never overwritten while
                    // the recording-store monitor was intentionally released.
                    val currentItems = load(context)
                    val currentMatching = currentItems.filter { it.serverRecordingId == serverId }
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val forgotten = prefs.getStringSet(KEY_FORGOTTEN, emptySet())?.toMutableSet() ?: mutableSetOf()
                    forgotten.addAll((discoveredMatching + currentMatching).map { it.clientRecordingId })
                    val kept = currentItems.filterNot { it.serverRecordingId == serverId }
                    val arr = JSONArray().apply { kept.forEach { put(toJson(it)) } }
                    check(
                        prefs.edit()
                            .putStringSet(KEY_FORGOTTEN, forgotten)
                            .putString(KEY_ITEMS, arr.toString())
                            .commit()
                    )
                }
            }
        }

    @Synchronized
    fun localPathForVisibleId(context: Context, visibleId: String): String? {
        val client = visibleId.removePrefix("local:")
        return load(context).firstOrNull { it.clientRecordingId == client || it.serverRecordingId == visibleId }?.localAudioPath
    }

    fun displayStatus(status: String): String = when (status) {
        STATUS_LOCAL_RECORDING -> "녹음 중"
        STATUS_LOCAL_SAVED -> "로컬 저장됨"
        STATUS_EDIT_PENDING -> "편집 대기 중"
        STATUS_UPLOAD_PENDING -> "업로드 대기 중"
        STATUS_UPLOADING -> "업로드 중"
        STATUS_SERVER_ACCEPTED, STATUS_PROCESSING -> "처리 중"
        STATUS_COMPLETED -> "완료"
        STATUS_UPLOAD_FAILED -> "업로드 대기 중"
        STATUS_UPLOAD_REJECTED -> "업로드 불가"
        STATUS_UPLOAD_AUTH_BLOCKED -> "로그인 확인 필요"
        STATUS_UPLOAD_AUTH_PROBE_PENDING -> "로그인 확인 중"
        else -> status
    }

    private fun fromJson(o: JSONObject): LocalRecording = LocalRecording(
        clientRecordingId = o.optString("client_recording_id"),
        localAudioPath = o.optString("local_audio_path"),
        createdAt = o.optString("created_at"),
        durationMs = o.optLong("duration_ms", 0L),
        uploadStatus = o.optString("upload_status", STATUS_UPLOAD_PENDING),
        serverRecordingId = o.optString("server_recording_id").ifBlank { null },
        retryCount = o.optInt("retry_count", 0),
        lastError = o.optString("last_error").ifBlank { null },
        createdTimestamp = o.optLong("created_timestamp", System.currentTimeMillis()),
        updatedTimestamp = o.optLong("updated_timestamp", System.currentTimeMillis()),
        localTitle = o.optString("local_title").ifBlank { null },
        serverTitle = o.optString("server_title").ifBlank { null }
    )

    private fun toJson(r: LocalRecording): JSONObject = JSONObject().apply {
        put("client_recording_id", r.clientRecordingId)
        put("local_audio_path", r.localAudioPath)
        put("created_at", r.createdAt)
        put("duration_ms", r.durationMs)
        put("upload_status", r.uploadStatus)
        put("server_recording_id", r.serverRecordingId ?: "")
        put("retry_count", r.retryCount)
        put("last_error", r.lastError ?: "")
        put("created_timestamp", r.createdTimestamp)
        put("updated_timestamp", r.updatedTimestamp)
        put("local_title", r.localTitle ?: "")
        put("server_title", r.serverTitle ?: "")
    }
}
