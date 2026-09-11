package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class UploadRecoveryPolicyTest {
    private fun candidate(
        id: String = "one",
        status: String = "UPLOAD_PENDING",
        serverId: String? = null,
        forgotten: Boolean = false,
        editState: String? = null,
        fileExists: Boolean = true,
        fileSize: Long = 4096L,
        path: String = "/tmp/audio.m4a",
        fileValid: Boolean = true
    ) = UploadRecoveryPolicy.Candidate(
        clientRecordingId = id,
        status = status,
        serverRecordingId = serverId,
        forgotten = forgotten,
        editState = editState,
        localPath = path,
        fileExists = fileExists,
        fileSize = fileSize,
        fileValid = fileValid
    )

    @Test fun selectsAtMostOneRecoveryUpload() {
        val selected = UploadRecoveryPolicy.selectNext(listOf(candidate("one"), candidate("two"), candidate("three")))
        assertEquals("one", selected?.clientRecordingId)
        assertEquals(1, UploadRecoveryPolicy.MAX_CONCURRENT_UPLOADS)
    }

    @Test fun newPendingHandoffsPrecedeCrashLeftUploadingBacklog() {
        val ordered = listOf(candidate("stale", status = "UPLOADING"), candidate("fresh", status = "UPLOAD_PENDING"))
            .sortedBy { UploadRecoveryPolicy.recoveryPriority(it.status) }
        assertEquals("fresh", UploadRecoveryPolicy.selectNext(ordered)?.clientRecordingId)
    }

    @Test fun everyRecordingHasItsOwnStableUniqueWorkName() {
        assertEquals(UploadRecoveryPolicy.workName("one"), UploadRecoveryPolicy.workName("one"))
        assertNotEquals(UploadRecoveryPolicy.workName("one"), UploadRecoveryPolicy.workName("two"))
    }

    @Test fun restartStatesAreRecoverableButEditPendingIsNot() {
        assertEquals(UploadRecoveryPolicy.Decision.UPLOAD, UploadRecoveryPolicy.decision(candidate(status = "UPLOAD_PENDING")))
        assertEquals(UploadRecoveryPolicy.Decision.UPLOAD, UploadRecoveryPolicy.decision(candidate(status = "UPLOADING")))
        assertEquals(UploadRecoveryPolicy.Decision.UPLOAD, UploadRecoveryPolicy.decision(candidate(status = "UPLOAD_FAILED")))
        assertEquals(UploadRecoveryPolicy.Decision.WAIT_EDIT, UploadRecoveryPolicy.decision(candidate(status = "EDIT_PENDING")))
    }

    @Test fun authBlockedRequiresAControlledTriggerAndSelectsOnlyOne() {
        val blocked = listOf(
            candidate("one", status = "UPLOAD_AUTH_BLOCKED"),
            candidate("two", status = "UPLOAD_AUTH_BLOCKED")
        )

        assertEquals(null, UploadRecoveryPolicy.selectControlledRetry(blocked, UploadRecoveryPolicy.RecoveryTrigger.AUTOMATIC))
        assertEquals("one", UploadRecoveryPolicy.selectControlledRetry(blocked, UploadRecoveryPolicy.RecoveryTrigger.PROCESS_START)?.clientRecordingId)
        assertEquals("one", UploadRecoveryPolicy.selectControlledRetry(blocked, UploadRecoveryPolicy.RecoveryTrigger.MANUAL)?.clientRecordingId)
        assertEquals(UploadRecoveryPolicy.Decision.SKIP_NOT_PENDING, UploadRecoveryPolicy.decision(blocked.first()))
    }

    @Test fun durableExistingProbeIsResumedBeforeAnySecondProbeIsReleased() {
        val candidates = listOf(
            candidate("blocked", status = "UPLOAD_AUTH_BLOCKED"),
            candidate("probe", status = "UPLOAD_AUTH_PROBE_PENDING")
        )

        assertEquals(
            "probe",
            UploadRecoveryPolicy.selectControlledRetry(
                candidates,
                UploadRecoveryPolicy.RecoveryTrigger.PROCESS_START
            )?.clientRecordingId
        )
        assertEquals(
            UploadRecoveryPolicy.Decision.UPLOAD,
            UploadRecoveryPolicy.decision(candidates.last())
        )
    }

    @Test fun invalidDurableProbeIsRetiredBeforeBlockedReplacementIsPromoted() {
        val candidates = listOf(
            candidate("missing-probe", status = "UPLOAD_AUTH_PROBE_PENDING", fileExists = false),
            candidate("blocked", status = "UPLOAD_AUTH_BLOCKED")
        )

        val normalization = UploadRecoveryPolicy.normalizeAuthProbes(candidates)
        assertEquals(null, normalization.retainedProbeIndex)
        assertEquals(
            listOf(
                UploadRecoveryPolicy.ProbeRetirement(
                    candidateIndex = 0,
                    status = "UPLOAD_FAILED",
                    error = "local audio missing"
                )
            ),
            normalization.retirements
        )

        val normalized = candidates.mapIndexed { index, item ->
            normalization.retirements.firstOrNull { it.candidateIndex == index }
                ?.let { item.copy(status = it.status) } ?: item
        }.toMutableList()
        val replacement = UploadRecoveryPolicy.selectControlledRetry(
            normalized,
            UploadRecoveryPolicy.RecoveryTrigger.PROCESS_START
        )
        val replacementIndex = normalized.indexOf(replacement)
        normalized[replacementIndex] = replacement!!.copy(status = "UPLOAD_AUTH_PROBE_PENDING")

        assertEquals(1, normalized.count { it.status == "UPLOAD_AUTH_PROBE_PENDING" })
        assertEquals("blocked", normalized.single { it.status == "UPLOAD_AUTH_PROBE_PENDING" }.clientRecordingId)
        assertEquals(true, UploadAuthCircuitPolicy.canClaim("UPLOAD_AUTH_PROBE_PENDING", normalized.map { it.status }))
    }

    @Test fun corruptAndDuplicateDurableProbesAreRetiredWithOneClaimableProbeRetained() {
        val candidates = listOf(
            candidate("valid", status = "UPLOAD_AUTH_PROBE_PENDING"),
            candidate("duplicate", status = "UPLOAD_AUTH_PROBE_PENDING"),
            candidate("corrupt", status = "UPLOAD_AUTH_PROBE_PENDING", fileValid = false),
            candidate("bad-metadata", status = "UPLOAD_AUTH_PROBE_PENDING", path = "")
        )

        val normalization = UploadRecoveryPolicy.normalizeAuthProbes(candidates)

        assertEquals(0, normalization.retainedProbeIndex)
        assertEquals(
            listOf(
                UploadRecoveryPolicy.ProbeRetirement(1, "UPLOAD_AUTH_BLOCKED", "duplicate auth probe retired"),
                UploadRecoveryPolicy.ProbeRetirement(2, "UPLOAD_FAILED", "local audio corrupt"),
                UploadRecoveryPolicy.ProbeRetirement(3, "UPLOAD_FAILED", "local audio corrupt metadata")
            ),
            normalization.retirements
        )
    }

    @Test fun authCircuitBlocksEveryAutomaticCandidateButAllowsExactlyTheDurableProbe() {
        val statuses = listOf("UPLOAD_AUTH_BLOCKED", "UPLOAD_PENDING", "UPLOAD_FAILED")

        assertEquals(true, UploadAuthCircuitPolicy.isActive(statuses))
        assertEquals(false, UploadAuthCircuitPolicy.canAutomaticallyEnqueue("UPLOAD_PENDING", statuses))
        assertEquals(false, UploadAuthCircuitPolicy.canClaim("UPLOAD_PENDING", statuses))
        assertEquals(false, UploadAuthCircuitPolicy.shouldEnqueueNext(statuses))

        val withProbe = listOf("UPLOAD_AUTH_BLOCKED", "UPLOAD_AUTH_PROBE_PENDING", "UPLOAD_PENDING")
        assertEquals(true, UploadAuthCircuitPolicy.canClaim("UPLOAD_AUTH_PROBE_PENDING", withProbe))
        assertEquals(false, UploadAuthCircuitPolicy.canClaim("UPLOAD_PENDING", withProbe))
    }

    @Test fun probeOutcomesKeepOrReleaseCircuitSafely() {
        assertEquals(false, UploadAuthCircuitPolicy.releasesCircuit(UploadAuthCircuitPolicy.ProbeOutcome.AUTH_BLOCKED))
        assertEquals(false, UploadAuthCircuitPolicy.releasesCircuit(UploadAuthCircuitPolicy.ProbeOutcome.TRANSIENT))
        assertEquals(true, UploadAuthCircuitPolicy.releasesCircuit(UploadAuthCircuitPolicy.ProbeOutcome.SUCCESS))
        assertEquals(true, UploadAuthCircuitPolicy.releasesCircuit(UploadAuthCircuitPolicy.ProbeOutcome.PERMANENT))
        assertEquals("UPLOAD_AUTH_BLOCKED", UploadAuthCircuitPolicy.probeStatus(UploadAuthCircuitPolicy.ProbeOutcome.AUTH_BLOCKED))
        assertEquals("UPLOAD_AUTH_PROBE_PENDING", UploadAuthCircuitPolicy.probeStatus(UploadAuthCircuitPolicy.ProbeOutcome.TRANSIENT))
    }

    @Test fun independentlyQueuedSecondCandidateCannotOpenNetworkUntilControlledProbeSucceeds() {
        val queue = FakeAuthCircuitQueue(linkedMapOf("first" to "UPLOAD_PENDING", "second" to "UPLOAD_PENDING"))
        val firstBlocked = CountDownLatch(1)
        val secondDone = CountDownLatch(1)

        val first = Thread {
            assertEquals(true, queue.claimAndOpen("first"))
            queue.authBlock("first")
            firstBlocked.countDown()
        }
        val second = Thread {
            firstBlocked.await()
            assertEquals(false, queue.claimAndOpen("second"))
            secondDone.countDown()
        }
        first.start()
        second.start()
        first.join()
        secondDone.await()
        second.join()

        assertEquals(1, queue.networkOpens.get())
        assertEquals("first", queue.releaseOneControlledProbe())
        assertEquals(true, queue.claimAndOpen("first"))
        queue.probeSucceeded("first")
        assertEquals(true, queue.claimAndOpen("second"))
        assertEquals(3, queue.networkOpens.get())
    }

    private class FakeAuthCircuitQueue(initial: LinkedHashMap<String, String>) {
        private val statuses = initial
        val networkOpens = AtomicInteger(0)

        @Synchronized fun claimAndOpen(id: String): Boolean {
            val status = statuses[id] ?: return false
            if (!UploadAuthCircuitPolicy.canClaim(status, statuses.values)) return false
            networkOpens.incrementAndGet()
            if (status != "UPLOAD_AUTH_PROBE_PENDING") statuses[id] = "UPLOADING"
            return true
        }

        @Synchronized fun authBlock(id: String) {
            statuses[id] = "UPLOAD_AUTH_BLOCKED"
        }

        @Synchronized fun releaseOneControlledProbe(): String? {
            val selected = statuses.entries.firstOrNull { it.value == "UPLOAD_AUTH_PROBE_PENDING" }
                ?: statuses.entries.firstOrNull { it.value == "UPLOAD_AUTH_BLOCKED" }
                ?: return null
            selected.setValue("UPLOAD_AUTH_PROBE_PENDING")
            return selected.key
        }

        @Synchronized fun probeSucceeded(id: String) {
            statuses[id] = "PROCESSING"
            statuses.entries.filter { it.value == "UPLOAD_AUTH_BLOCKED" }
                .forEach { it.setValue("UPLOAD_PENDING") }
        }
    }

    @Test fun forgottenCancelledCompletedAndLeasedNeverUpload() {
        assertEquals(UploadRecoveryPolicy.Decision.SKIP_FORGOTTEN, UploadRecoveryPolicy.decision(candidate(forgotten = true)))
        assertEquals(UploadRecoveryPolicy.Decision.SKIP_CANCELLED, UploadRecoveryPolicy.decision(candidate(status = "CANCELLED")))
        assertEquals(UploadRecoveryPolicy.Decision.SKIP_SERVER_CONFIRMED, UploadRecoveryPolicy.decision(candidate(serverId = "server-1")))
        assertEquals(UploadRecoveryPolicy.Decision.WAIT_EDIT, UploadRecoveryPolicy.decision(candidate(editState = "EDITING")))
        assertEquals(UploadRecoveryPolicy.Decision.WAIT_EDIT, UploadRecoveryPolicy.decision(candidate(editState = "EDIT_RECOVERY_REQUIRED")))
    }

    @Test fun uploadExecutionGateAllowsOnlyOneThread() {
        assertEquals(true, UploadExecutionGate.tryAcquire())
        try {
            var secondAcquired = true
            val thread = Thread { secondAcquired = UploadExecutionGate.tryAcquire() }
            thread.start()
            thread.join()
            assertEquals(false, secondAcquired)
        } finally {
            UploadExecutionGate.release()
        }
    }

    @Test fun missingAndCorruptInputsAreReportedNotUploaded() {
        assertEquals(UploadRecoveryPolicy.Decision.REPORT_MISSING, UploadRecoveryPolicy.decision(candidate(fileExists = false)))
        assertEquals(UploadRecoveryPolicy.Decision.REPORT_CORRUPT_FILE, UploadRecoveryPolicy.decision(candidate(fileSize = 20L)))
        assertEquals(UploadRecoveryPolicy.Decision.REPORT_CORRUPT_FILE, UploadRecoveryPolicy.decision(candidate(fileSize = 4096L, fileValid = false)))
        assertEquals(UploadRecoveryPolicy.Decision.REPORT_CORRUPT_METADATA, UploadRecoveryPolicy.decision(candidate(path = "")))
        assertEquals(UploadRecoveryPolicy.Decision.REPORT_CORRUPT_METADATA, UploadRecoveryPolicy.decision(candidate(id = "")))
    }
}
