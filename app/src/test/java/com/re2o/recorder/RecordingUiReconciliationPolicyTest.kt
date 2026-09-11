package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingUiReconciliationPolicyTest {
    @Test fun stoppedRecorderRemainsFinalizingUntilExactPendingIntentClears() {
        val decision = RecordingUiReconciliationPolicy.reconcile(
            uiIsRecording = true,
            uiIsStopping = true,
            durableIsRecording = false,
            durableStopPending = true,
            isHomeScreen = true
        )

        assertFalse(decision.isRecording)
        assertTrue(decision.isStopping)
        assertFalse(decision.runTicker)
        assertEquals(RecordingUiReconciliationPolicy.Render.FINALIZING, decision.render)
    }

    @Test fun dispatchedStopStaysStoppingWhileServiceStillOwnsRecordingTruth() {
        val decision = RecordingUiReconciliationPolicy.reconcile(
            uiIsRecording = true,
            uiIsStopping = true,
            durableIsRecording = true,
            durableStopPending = true,
            isHomeScreen = true
        )

        assertTrue(decision.isRecording)
        assertTrue(decision.isStopping)
        assertFalse(decision.runTicker)
        assertEquals(RecordingUiReconciliationPolicy.Render.STOPPING, decision.render)
    }

    @Test fun processRecreationRestoresStoppingFromDurablePendingIntent() {
        val decision = RecordingUiReconciliationPolicy.reconcile(
            uiIsRecording = false,
            uiIsStopping = false,
            durableIsRecording = true,
            durableStopPending = true,
            isHomeScreen = true
        )

        assertTrue(decision.isRecording)
        assertTrue(decision.isStopping)
        assertFalse(decision.runTicker)
        assertEquals(RecordingUiReconciliationPolicy.Render.STOPPING, decision.render)
    }

    @Test fun durableRecordingRestoresRecordingUiAndTickerOnHome() {
        val decision = RecordingUiReconciliationPolicy.reconcile(
            uiIsRecording = false,
            uiIsStopping = false,
            durableIsRecording = true,
            durableStopPending = false,
            isHomeScreen = true
        )

        assertTrue(decision.isRecording)
        assertFalse(decision.isStopping)
        assertTrue(decision.runTicker)
        assertEquals(RecordingUiReconciliationPolicy.Render.RECORDING, decision.render)
    }

    @Test fun matchingDurableStateDoesNotOverwriteTransientHomeStatus() {
        val decision = RecordingUiReconciliationPolicy.reconcile(
            uiIsRecording = false,
            uiIsStopping = false,
            durableIsRecording = false,
            durableStopPending = false,
            isHomeScreen = true
        )

        assertEquals(RecordingUiReconciliationPolicy.Render.KEEP, decision.render)
        assertFalse(decision.runTicker)
    }

    @Test fun exactPendingClearMakesIdleAvailableAfterFinalization() {
        val decision = RecordingUiReconciliationPolicy.reconcile(
            uiIsRecording = false,
            uiIsStopping = true,
            durableIsRecording = false,
            durableStopPending = false,
            isHomeScreen = true
        )

        assertFalse(decision.isRecording)
        assertFalse(decision.isStopping)
        assertFalse(decision.runTicker)
        assertEquals(RecordingUiReconciliationPolicy.Render.IDLE, decision.render)
    }

    @Test fun nonHomeScreenReconcilesStateWithoutReplacingCurrentScreen() {
        val decision = RecordingUiReconciliationPolicy.reconcile(
            uiIsRecording = true,
            uiIsStopping = true,
            durableIsRecording = false,
            durableStopPending = true,
            isHomeScreen = false
        )

        assertFalse(decision.isRecording)
        assertTrue(decision.isStopping)
        assertFalse(decision.runTicker)
        assertEquals(RecordingUiReconciliationPolicy.Render.KEEP, decision.render)
    }
}
