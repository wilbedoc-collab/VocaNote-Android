package com.re2o.recorder

import java.util.concurrent.locks.ReentrantLock

/** Pure selection policy for restart-safe, one-at-a-time upload recovery. */
object UploadRecoveryPolicy {
    const val MAX_CONCURRENT_UPLOADS = 1
    private const val MIN_AUDIO_BYTES = 2048L
    private val uploadableStates = setOf("UPLOAD_PENDING", "UPLOAD_FAILED", "UPLOADING", "UPLOAD_AUTH_PROBE_PENDING")
    private val editBlockedStates = setOf("EDIT_PENDING", "EDITING", "EDIT_RECOVERY_REQUIRED")
    private val cancelledStates = setOf("CANCELLED", "STALE", "FORGOTTEN")

    enum class RecoveryTrigger { AUTOMATIC, PROCESS_START, MANUAL }

    data class Candidate(
        val clientRecordingId: String,
        val status: String,
        val serverRecordingId: String?,
        val forgotten: Boolean,
        val editState: String?,
        val localPath: String,
        val fileExists: Boolean,
        val fileSize: Long,
        val fileValid: Boolean
    )

    enum class Decision {
        UPLOAD,
        WAIT_EDIT,
        SKIP_FORGOTTEN,
        SKIP_CANCELLED,
        SKIP_SERVER_CONFIRMED,
        SKIP_NOT_PENDING,
        REPORT_MISSING,
        REPORT_CORRUPT_FILE,
        REPORT_CORRUPT_METADATA
    }

    data class ProbeRetirement(
        val candidateIndex: Int,
        val status: String,
        val error: String?
    )

    data class AuthProbeNormalization(
        val retainedProbeIndex: Int?,
        val retirements: List<ProbeRetirement>
    )

    fun decision(candidate: Candidate): Decision {
        if (candidate.forgotten || candidate.status == "FORGOTTEN") return Decision.SKIP_FORGOTTEN
        if (candidate.status in cancelledStates) return Decision.SKIP_CANCELLED
        if (!candidate.serverRecordingId.isNullOrBlank()) return Decision.SKIP_SERVER_CONFIRMED
        if (candidate.editState in editBlockedStates || candidate.status == "EDIT_PENDING") return Decision.WAIT_EDIT
        if (candidate.status !in uploadableStates) return Decision.SKIP_NOT_PENDING
        if (candidate.clientRecordingId.isBlank() || candidate.localPath.isBlank()) return Decision.REPORT_CORRUPT_METADATA
        if (!candidate.fileExists) return Decision.REPORT_MISSING
        if (candidate.fileSize <= MIN_AUDIO_BYTES || !candidate.fileValid) return Decision.REPORT_CORRUPT_FILE
        return Decision.UPLOAD
    }

    fun recoveryPriority(status: String): Int = when (status) {
        "UPLOAD_PENDING" -> 0
        "UPLOAD_FAILED" -> 1
        "UPLOADING" -> 2
        else -> 3
    }

    fun selectNext(candidates: List<Candidate>): Candidate? =
        candidates.firstOrNull { decision(it) == Decision.UPLOAD }

    /** AUTH_BLOCKED is considered only at an explicit bounded recovery boundary. */
    fun selectControlledRetry(candidates: List<Candidate>, trigger: RecoveryTrigger): Candidate? {
        if (trigger == RecoveryTrigger.AUTOMATIC) return null
        return candidates.firstOrNull { candidate ->
            candidate.status == UploadAuthCircuitPolicy.PROBE_PENDING &&
                decision(candidate) == Decision.UPLOAD
        } ?: candidates.firstOrNull { candidate ->
            candidate.status == "UPLOAD_AUTH_BLOCKED" &&
                decision(candidate.copy(status = "UPLOAD_PENDING")) == Decision.UPLOAD
        }
    }

    /** Keeps at most one valid durable probe and safely retires every other probe row. */
    fun normalizeAuthProbes(candidates: List<Candidate>): AuthProbeNormalization {
        var retainedProbeIndex: Int? = null
        val retirements = mutableListOf<ProbeRetirement>()
        candidates.forEachIndexed { index, candidate ->
            if (candidate.status != UploadAuthCircuitPolicy.PROBE_PENDING) return@forEachIndexed
            when (val probeDecision = decision(candidate)) {
                Decision.UPLOAD -> if (retainedProbeIndex == null) {
                    retainedProbeIndex = index
                } else {
                    retirements.add(ProbeRetirement(index, UploadAuthCircuitPolicy.AUTH_BLOCKED, "duplicate auth probe retired"))
                }
                Decision.REPORT_MISSING ->
                    retirements.add(ProbeRetirement(index, "UPLOAD_FAILED", "local audio missing"))
                Decision.REPORT_CORRUPT_FILE ->
                    retirements.add(ProbeRetirement(index, "UPLOAD_FAILED", "local audio corrupt"))
                Decision.REPORT_CORRUPT_METADATA ->
                    retirements.add(ProbeRetirement(index, "UPLOAD_FAILED", "local audio corrupt metadata"))
                Decision.SKIP_SERVER_CONFIRMED ->
                    retirements.add(ProbeRetirement(index, "PROCESSING", null))
                else -> retirements.add(
                    ProbeRetirement(index, "UPLOAD_FAILED", "auth probe invalid state: ${probeDecision.name}")
                )
            }
        }
        return AuthProbeNormalization(retainedProbeIndex, retirements)
    }

    fun workName(clientRecordingId: String): String = "vocanote_upload_$clientRecordingId"
}

/** Pure queue-wide policy. The row status is the durable circuit/probe lease. */
object UploadAuthCircuitPolicy {
    const val AUTH_BLOCKED = "UPLOAD_AUTH_BLOCKED"
    const val PROBE_PENDING = "UPLOAD_AUTH_PROBE_PENDING"

    enum class ProbeOutcome { SUCCESS, AUTH_BLOCKED, PERMANENT, TRANSIENT }

    fun isActive(statuses: Iterable<String>): Boolean =
        statuses.any { it == AUTH_BLOCKED || it == PROBE_PENDING }

    fun canAutomaticallyEnqueue(targetStatus: String, statuses: Iterable<String>): Boolean =
        targetStatus != PROBE_PENDING && !isActive(statuses)

    fun canClaim(targetStatus: String, statuses: Iterable<String>): Boolean {
        val snapshot = statuses.toList()
        return if (targetStatus == PROBE_PENDING) {
            snapshot.count { it == PROBE_PENDING } == 1
        } else {
            !isActive(snapshot)
        }
    }

    fun shouldEnqueueNext(statuses: Iterable<String>): Boolean = !isActive(statuses)

    fun releasesCircuit(outcome: ProbeOutcome): Boolean =
        outcome == ProbeOutcome.SUCCESS || outcome == ProbeOutcome.PERMANENT

    fun probeStatus(outcome: ProbeOutcome): String = when (outcome) {
        ProbeOutcome.SUCCESS -> "PROCESSING"
        ProbeOutcome.PERMANENT -> "UPLOAD_REJECTED"
        ProbeOutcome.AUTH_BLOCKED -> AUTH_BLOCKED
        ProbeOutcome.TRANSIENT -> PROBE_PENDING
    }
}

/** Process-local execution fence; contenders retry without opening a second network body. */
object UploadExecutionGate {
    private val lock = ReentrantLock()

    fun tryAcquire(): Boolean = lock.tryLock()

    fun release() {
        check(lock.isHeldByCurrentThread) { "upload gate is not owned by this thread" }
        lock.unlock()
    }
}
