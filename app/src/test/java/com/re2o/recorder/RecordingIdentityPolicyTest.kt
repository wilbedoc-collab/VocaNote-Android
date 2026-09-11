package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIdentityPolicyTest {
    private val recordingA = RecordingIdentityPolicy.Identity("/recordings/a.m4a", "client-a")
    private val recordingB = RecordingIdentityPolicy.Identity("/recordings/b.m4a", "client-b")

    @Test fun missedStartedBroadcastIsRecoveredOnlyFromExactDurableIdentity() {
        assertEquals(
            RecordingIdentityPolicy.StartOutcome.STARTED,
            RecordingIdentityPolicy.startOutcome(
                requested = recordingA,
                durableCurrent = recordingA,
                durableIsRecording = true,
                failed = null
            )
        )
        assertEquals(
            RecordingIdentityPolicy.StartOutcome.WAIT,
            RecordingIdentityPolicy.startOutcome(
                requested = recordingA,
                durableCurrent = recordingB,
                durableIsRecording = true,
                failed = null
            )
        )
    }

    @Test fun missedFailureBroadcastIsRecoveredOnlyFromExactDurableFailure() {
        assertEquals(
            RecordingIdentityPolicy.StartOutcome.FAILED,
            RecordingIdentityPolicy.startOutcome(
                requested = recordingA,
                durableCurrent = RecordingIdentityPolicy.Identity(null, null),
                durableIsRecording = false,
                failed = recordingA
            )
        )
    }

    @Test fun secondStartBIsRejectedWhileRecorderAIsActive() {
        assertEquals(
            false,
            RecordingIdentityPolicy.canStart(
                durableIsRecording = true,
                finalizationPending = false,
                recorderAlreadyActive = true
            )
        )
    }

    @Test fun startIsAllowedOnlyFromTerminalIdleState() {
        assertEquals(
            true,
            RecordingIdentityPolicy.canStart(
                durableIsRecording = false,
                finalizationPending = false,
                recorderAlreadyActive = false
            )
        )
    }

    @Test fun staleARecoveryEnqueuesExactAWithoutStoppingCurrentB() {
        val action = RecordingIdentityPolicy.recoverPending(
            pending = recordingA,
            current = recordingB,
            durableIsRecording = true
        )

        assertEquals(RecordingIdentityPolicy.Action.ENQUEUE_EXACT_PENDING, action)
    }

    @Test fun matchingARecoveryMayStopOnlyExactCurrentA() {
        val action = RecordingIdentityPolicy.recoverPending(
            pending = recordingA,
            current = recordingA,
            durableIsRecording = true
        )

        assertEquals(RecordingIdentityPolicy.Action.STOP_EXACT_CURRENT, action)
    }

    @Test fun processDeathWithStaleAIntentStillNeverStopsRestoredB() {
        val action = RecordingIdentityPolicy.recoverPending(
            pending = recordingA,
            current = recordingB,
            durableIsRecording = true
        )

        assertEquals(RecordingIdentityPolicy.Action.ENQUEUE_EXACT_PENDING, action)
    }

    @Test fun staleExplicitStopForAEnqueuesAAndDoesNotStopB() {
        val action = RecordingIdentityPolicy.handleStop(
            requested = recordingA,
            pending = recordingA,
            current = recordingB,
            durableIsRecording = true
        )

        assertEquals(RecordingIdentityPolicy.Action.ENQUEUE_EXACT_PENDING, action)
    }

    @Test fun notificationStopWithoutIdentityStopsOnlyDurableCurrentB() {
        val action = RecordingIdentityPolicy.handleStop(
            requested = null,
            pending = null,
            current = recordingB,
            durableIsRecording = true
        )

        assertEquals(RecordingIdentityPolicy.Action.STOP_EXACT_CURRENT, action)
    }

    @Test fun notificationStopWithoutDurableCurrentNeverFallsBackToPending() {
        val action = RecordingIdentityPolicy.handleStop(
            requested = null,
            pending = recordingA,
            current = recordingA,
            durableIsRecording = false
        )

        assertEquals(RecordingIdentityPolicy.Action.IGNORE, action)
    }

    @Test fun startWithBlankIdentityFailsClosed() {
        assertEquals(
            false,
            RecordingIdentityPolicy.canStart(
                requested = RecordingIdentityPolicy.Identity("/recordings/a.m4a", " "),
                durablePending = recordingA,
                durableIsRecording = false,
                finalizationPending = false,
                recorderAlreadyActive = false
            )
        )
    }

    @Test fun startRequiresExactDurablePendingIdentity() {
        assertEquals(
            true,
            RecordingIdentityPolicy.canStart(
                requested = recordingA,
                durablePending = recordingA,
                durableIsRecording = false,
                finalizationPending = false,
                recorderAlreadyActive = false
            )
        )
        assertEquals(
            false,
            RecordingIdentityPolicy.canStart(
                requested = recordingA,
                durablePending = recordingB,
                durableIsRecording = false,
                finalizationPending = false,
                recorderAlreadyActive = false
            )
        )
    }

    @Test fun finalizationPublishAndClearRequireExactIdentity() {
        val a = FinalizationIdentityPolicy.Identity("/recordings/a.m4a", "client-a")
        val b = FinalizationIdentityPolicy.Identity("/recordings/b.m4a", "client-b")

        assertTrue(FinalizationIdentityPolicy.canPublish(null, a, a, durableIsRecording = true))
        assertTrue(FinalizationIdentityPolicy.canPublish(a, a, a, durableIsRecording = true))
        assertFalse(FinalizationIdentityPolicy.canPublish(a, b, b, durableIsRecording = true))
        assertFalse(FinalizationIdentityPolicy.canPublish(null, a, b, durableIsRecording = true))
        assertTrue(FinalizationIdentityPolicy.canClear(a, a))
        assertFalse(FinalizationIdentityPolicy.canClear(b, a))
    }

    @Test fun unknownExplicitIdentityIsIgnoredWithoutCurrentOrPendingFallback() {
        val action = RecordingIdentityPolicy.handleStop(
            requested = RecordingIdentityPolicy.Identity("/recordings/c.m4a", "client-c"),
            pending = recordingA,
            current = recordingB,
            durableIsRecording = true
        )

        assertEquals(RecordingIdentityPolicy.Action.IGNORE, action)
    }
}
