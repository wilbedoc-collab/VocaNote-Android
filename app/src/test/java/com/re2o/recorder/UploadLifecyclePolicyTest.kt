package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadLifecyclePolicyTest {
    @Test fun localForgetIsDeferredOnceUploadClaimExists() {
        assertEquals(
            UploadLifecyclePolicy.ForgetDecision.DEFER_UPLOAD_IN_FLIGHT,
            UploadLifecyclePolicy.forgetDecision("UPLOADING", null)
        )
    }

    @Test fun localForgetIsDeferredWhileDurableAuthProbeOwnsUploadLease() {
        assertEquals(
            UploadLifecyclePolicy.ForgetDecision.DEFER_UPLOAD_IN_FLIGHT,
            UploadLifecyclePolicy.forgetDecision(UploadAuthCircuitPolicy.PROBE_PENDING, null)
        )
    }

    @Test fun localForgetBeforeClaimIsAllowedIncludingEditPending() {
        assertEquals(
            UploadLifecyclePolicy.ForgetDecision.DELETE_LOCALLY,
            UploadLifecyclePolicy.forgetDecision("UPLOAD_PENDING", null)
        )
        assertEquals(
            UploadLifecyclePolicy.ForgetDecision.DELETE_LOCALLY,
            UploadLifecyclePolicy.forgetDecision("EDIT_PENDING", null)
        )
    }

    @Test fun mappedRecordRequiresServerDeleteInsteadOfLocalForget() {
        assertEquals(
            UploadLifecyclePolicy.ForgetDecision.REQUIRE_SERVER_DELETE,
            UploadLifecyclePolicy.forgetDecision("PROCESSING", "server-one")
        )
    }

    @Test fun permanentRecordHttpErrorsAreTerminalButAuthBlocksAndTransientErrorsRetry() {
        listOf(400, 404, 405, 409, 413, 415, 422).forEach {
            assertEquals("HTTP $it", UploadFailurePolicy.Disposition.TERMINAL, UploadFailurePolicy.classifyHttp(it))
        }
        listOf(401, 403).forEach {
            assertEquals("HTTP $it", UploadFailurePolicy.Disposition.AUTH_BLOCKED, UploadFailurePolicy.classifyHttp(it))
        }
        listOf(408, 425, 429, 500, 503).forEach {
            assertEquals("HTTP $it", UploadFailurePolicy.Disposition.RETRY, UploadFailurePolicy.classifyHttp(it))
        }
    }

    @Test fun terminalRecordDoesNotStarveNextPendingRecord() {
        val terminal = candidate("bad", "UPLOAD_REJECTED")
        val pending = candidate("next", "UPLOAD_PENDING")
        assertEquals("next", UploadRecoveryPolicy.selectNext(listOf(terminal, pending))?.clientRecordingId)
    }

    @Test fun authBlockedCanBeResetOnlyByRestartOrManualRecovery() {
        assertEquals(false, UploadFailurePolicy.canResetAuthBlocked("UPLOAD_AUTH_BLOCKED", controlled = false))
        assertEquals(true, UploadFailurePolicy.canResetAuthBlocked("UPLOAD_AUTH_BLOCKED", controlled = true))
        assertEquals(false, UploadFailurePolicy.canResetAuthBlocked("UPLOAD_REJECTED", controlled = true))
    }

    @Test fun authBlockedStopsQueueHandoffButOtherTerminalAndTransientOutcomesAdvance() {
        assertEquals(false, UploadFailurePolicy.shouldEnqueueNext("UPLOAD_AUTH_BLOCKED"))
        assertEquals(true, UploadFailurePolicy.shouldEnqueueNext("UPLOAD_REJECTED"))
        assertEquals(true, UploadFailurePolicy.shouldEnqueueNext("UPLOAD_PENDING"))
        assertEquals(true, UploadFailurePolicy.shouldEnqueueNext("PROCESSING"))
    }

    private fun candidate(id: String, status: String) = UploadRecoveryPolicy.Candidate(
        clientRecordingId = id,
        status = status,
        serverRecordingId = null,
        forgotten = false,
        editState = null,
        localPath = "/tmp/$id.m4a",
        fileExists = true,
        fileSize = 4096,
        fileValid = true
    )
}
