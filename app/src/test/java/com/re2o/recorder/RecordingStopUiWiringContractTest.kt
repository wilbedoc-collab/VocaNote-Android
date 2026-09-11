package com.re2o.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingStopUiWiringContractTest {
    private fun source(relativePath: String): String =
        File("src/main/java/com/re2o/recorder/$relativePath").readText()

    @Test fun servicePublishesStopCompletionOnlyAfterDurableStopAndFinalizerEnqueue() {
        val service = source("RecordingService.kt")
        val stopFlow = service.substringAfter("ACTION_STOP -> {").substringBefore("stopForeground")
        val durableStopAt = stopFlow.indexOf("putBoolean(KEY_IS_RECORDING, false)")
        val enqueueAt = stopFlow.indexOf("enqueueFinalizer(path, clientId, startedAt, stoppedAt)")
        val broadcastAt = stopFlow.indexOf("sendBroadcast(Intent(ACTION_RECORDING_STOPPED)")

        assertTrue(durableStopAt >= 0)
        assertTrue(durableStopAt > enqueueAt)
        assertTrue(broadcastAt > durableStopAt)
        assertTrue(stopFlow.contains("setPackage(packageName)"))
    }

    @Test fun activityReconcilesDurableRecordingStateOnResumeAndStopBroadcast() {
        val activity = source("MainActivity.kt")
        val onResume = activity.substringAfter("override fun onResume()")
            .substringBefore("private fun openSafeEditor")
        val receiver = activity.substringAfter("private val recordingStateReceiver")
            .substringBefore("private lateinit var historyButton")

        assertTrue(onResume.contains("ACTION_RECORDING_STOPPED"))
        assertTrue(onResume.contains("reconcileRecordingUiFromDurableState()"))
        assertTrue(receiver.contains("ACTION_RECORDING_STOPPED"))
        assertTrue(receiver.contains("reconcileRecordingUiFromDurableState()"))
    }

    @Test fun stoppingUiDisablesEveryRecordAndStopEntryPoint() {
        val activity = source("MainActivity.kt")
        val stopping = activity.substringAfter("private fun showStoppingStatus()")
            .substringBefore("private fun showUploadingStatus")
        val start = activity.substringAfter("private fun startRecordingIfReady()")
            .substringBefore("private fun startRecordingStatusTicker")
        val stop = activity.substringAfter("private fun stopRecordingAndUpload()")
            .substringBefore("private fun fetchLatestResult")

        assertTrue(activity.contains("private var isStopping = false"))
        assertTrue(stopping.contains("recordButton.isEnabled = false"))
        assertTrue(stopping.contains("recordButtonFrameView?.isEnabled = false"))
        assertTrue(stopping.contains("setOnClickListener(null)"))
        assertTrue(start.contains("if (isStarting || isRecording || isStopping) return"))
        assertTrue(stop.contains("if (isStarting || !isRecording || isStopping) return"))
        assertTrue(stop.contains("isStopping = true"))
        assertTrue(stop.contains("stopRecordingStatusTicker()"))
    }

    @Test fun activityNeverPersistsFalseServiceOwnedRecordingTruth() {
        val activity = source("MainActivity.kt")
        val finalizer = source("RecordingFinalizerWorker.kt")

        assertFalse(activity.contains("putBoolean(KEY_IS_RECORDING, false)"))
        assertFalse(activity.contains("saveRecordingState(path, false)"))
        assertFalse(finalizer.contains("putBoolean(RecordingService.KEY_IS_RECORDING, false)"))
        assertTrue(finalizer.contains("action = RecordingService.ACTION_STOP"))
    }

    @Test fun activityDoesNotWriteServiceOwnedTruthBeforeStartDispatch() {
        val activity = source("MainActivity.kt")
        val save = activity.substringAfter("private fun saveRecordingState").substringBefore("private fun startRecordingIfReady")
        val start = activity.substringAfter("private fun startRecordingIfReady").substringBefore("private fun startRecordingStatusTicker")

        assertFalse(save.contains("putBoolean(KEY_IS_RECORDING, true)"))
        assertFalse(save.contains("putString(KEY_CURRENT_PATH"))
        assertTrue(start.contains("prefs.getBoolean(KEY_IS_RECORDING, false)"))
    }

    @Test fun immediateStopIsDisabledUntilExactServiceStartAcknowledgement() {
        val activity = source("MainActivity.kt")
        val service = source("RecordingService.kt")
        val start = activity.substringAfter("private fun startRecordingIfReady").substringBefore("private fun startRecordingStatusTicker")
        val stop = activity.substringAfter("private fun stopRecordingAndUpload").substringBefore("private fun showUploadProgress")
        val serviceStart = service.substringAfter("ACTION_START -> {").substringBefore("ACTION_STOP -> {")

        assertTrue(activity.contains("private var isStarting = false"))
        assertTrue(start.indexOf("isStarting = true") < start.indexOf("startForegroundService(svc)"))
        assertFalse(start.contains("isRecording = true"))
        assertTrue(start.contains("showStartingStatus()"))
        assertTrue(stop.contains("if (isStarting || !isRecording || isStopping) return"))
        assertTrue(serviceStart.indexOf("startRecording(path)") < serviceStart.indexOf("ACTION_RECORDING_STARTED"))
        assertTrue(activity.contains("intent?.action == RecordingService.ACTION_RECORDING_STARTED"))
        assertTrue(activity.contains("intent.getStringExtra(RecordingService.EXTRA_PATH) == currentFile?.absolutePath"))
        assertTrue(activity.contains("intent.getStringExtra(RecordingService.EXTRA_CLIENT_ID) == currentClientRecordingId"))
    }

    @Test fun startSuccessBecomesDurableOnlyAfterMediaRecorderStarts() {
        val service = source("RecordingService.kt")
        val start = service.substringAfter("ACTION_START -> {").substringBefore("ACTION_STOP -> {")

        assertTrue(start.indexOf("startRecording(path)") < start.indexOf("putBoolean(KEY_IS_RECORDING, true)"))
        assertTrue(start.contains("if (!startRecording(path))"))
        assertTrue(start.contains("KEY_START_FAILED_PATH"))
        assertTrue(start.contains("publishStartFailure(path, clientId)"))
    }

    @Test fun resumeConsumesExactDurableStartOutcomeWhenBroadcastWasMissed() {
        val activity = source("MainActivity.kt")
        val reconcile = activity.substringAfter("private fun reconcileRecordingUiFromDurableState()")
            .substringBefore("private fun saveRecordingState")

        assertTrue(reconcile.contains("RecordingIdentityPolicy.startOutcome"))
        assertTrue(reconcile.contains("StartOutcome.STARTED"))
        assertTrue(reconcile.contains("StartOutcome.FAILED"))
    }

    @Test fun pendingStartIdentitySurvivesActivityRecreationWithoutUsingCurrentIdentity() {
        val activity = source("MainActivity.kt")
        val save = activity.substringAfter("private fun saveRecordingState").substringBefore("private fun startRecordingIfReady")
        val restore = activity.substringAfter("private fun restoreRecordingState").substringBefore("private fun reconcileRecordingUiFromDurableState")

        assertTrue(save.contains("RecordingService.KEY_START_PENDING_PATH"))
        assertTrue(save.contains("RecordingService.KEY_START_PENDING_CLIENT_ID"))
        assertTrue(save.contains(".commit()"))
        assertTrue(restore.contains("RecordingService.KEY_START_PENDING_PATH"))
        assertTrue(restore.contains("RecordingService.KEY_START_PENDING_CLIENT_ID"))
    }

    @Test fun waitingStartQueriesServiceSoMissedFailureAndCommitFailureCannotWedgeUi() {
        val activity = source("MainActivity.kt")
        val service = source("RecordingService.kt")
        val reconcile = activity.substringAfter("private fun reconcileRecordingUiFromDurableState()")
            .substringBefore("private fun saveRecordingState")

        assertTrue(reconcile.contains("RecordingService.ACTION_QUERY_START_OUTCOME"))
        assertTrue(service.contains("ACTION_QUERY_START_OUTCOME ->"))
        assertTrue(service.contains("publishStartFailure"))
    }

    @Test fun nullIntentRestartAndDestroyRecoverExactActiveRecorderForFinalization() {
        val service = source("RecordingService.kt")
        val command = service.substringAfter("override fun onStartCommand").substringBefore("private fun enqueueFinalizer")
        val destroy = service.substringAfter("override fun onDestroy()")

        assertTrue(command.contains("if (intent == null)"))
        assertTrue(command.contains("recoverInterruptedRecording()"))
        assertTrue(destroy.contains("recoverInterruptedRecording()"))
    }

    @Test fun malformedFinalizationBecomesExactTerminalFailureAndReleasesStartGate() {
        val finalizer = source("RecordingFinalizerWorker.kt")

        assertTrue(finalizer.contains("RecordingFinalizationPolicy.invalidFileAction"))
        assertTrue(finalizer.contains("STATUS_FINALIZATION_FAILED"))
        assertTrue(finalizer.contains("clearPendingAndNotify(applicationContext, path, clientId)"))
        assertTrue(finalizer.contains("lastError = reason"))
    }

    @Test fun finalizerRequiresPlayableAudioAndMissingRecoveryIsTerminal() {
        val finalizer = source("RecordingFinalizerWorker.kt")

        assertTrue(finalizer.contains("FinalizedAudioValidator.isValid(file)"))
        assertTrue(finalizer.contains("RecordingFinalizationPolicy.recoveryAction(file.isFile)"))
        assertTrue(finalizer.contains("\"finalization source missing during restart recovery\""))
    }

    @Test fun existingRecoveryRowRequiresExactPendingFileBeforeShortCircuit() {
        val finalizer = source("RecordingFinalizerWorker.kt")

        assertTrue(finalizer.contains("RecordingFinalizationPolicy.canResumeExisting("))
        assertTrue(finalizer.contains("existing.localAudioPath"))
        assertTrue(finalizer.contains("\"existing finalization row has no exact source file\""))
    }

    @Test fun forgetAndFinalizerRegistrationShareAtomicTombstoneFence() {
        val store = source("LocalRecordingStore.kt")
        val register = store.substringAfter("fun registerSaved").substringBefore("fun recoveryDecision")
        val forget = store.substringAfter("fun tryForgetLocal").substringBefore("fun claimForUpload")

        assertTrue(register.contains("DeletionPurgeOperationLock.withLock"))
        assertTrue(register.contains("RecordingPersistencePolicy.canRegisterSaved"))
        assertTrue(forget.contains("DeletionPurgeOperationLock.withLock"))
    }

    @Test fun serviceStartsOnlyExactDurablePendingAndPreservesOtherPendingOnFailure() {
        val service = source("RecordingService.kt")
        val start = service.substringAfter("ACTION_START ->").substringBefore("ACTION_QUERY_START_OUTCOME")
        val query = service.substringAfter("ACTION_QUERY_START_OUTCOME ->").substringBefore("ACTION_STOP ->")

        assertTrue(start.contains("durablePending = durablePending"))
        assertTrue(start.contains("clearPendingIfExact = requested == durablePending"))
        assertTrue(start.contains("markInactive = false"))
        assertTrue(query.contains("intent.getStringExtra(EXTRA_PATH)"))
        assertTrue(query.contains("intent.getStringExtra(EXTRA_CLIENT_ID)"))
    }

    @Test fun reconciliationStopsExactTickerWithoutFinalizingOrUploadingAgain() {
        val activity = source("MainActivity.kt")
        val reconcile = activity.substringAfter("private fun reconcileRecordingUiFromDurableState()")
            .substringBefore("private fun saveRecordingState")
        val stopTicker = activity.substringAfter("private fun stopRecordingStatusTicker()")
            .substringBefore("private fun renderRecordingTick")

        assertTrue(reconcile.contains("getBoolean(KEY_IS_RECORDING, false)"))
        assertTrue(reconcile.contains("showIdleStatus()"))
        assertTrue(reconcile.contains("showStoppingStatus()"))
        assertTrue(reconcile.contains("showFinalizingStatus()"))
        assertTrue(reconcile.contains("stopRecordingStatusTicker()"))
        assertTrue(stopTicker.contains("handler.removeCallbacks(recordingStatusTicker)"))
        assertFalse(reconcile.contains("RecordingFinalizerWorker.enqueue"))
        assertFalse(reconcile.contains("VocaNoteUploadWorker.enqueue"))
        assertFalse(reconcile.contains("ACTION_STOP"))
    }

    @Test fun stalePendingRecoveryAndStopAreWiredThroughExactIdentityPolicy() {
        val service = source("RecordingService.kt")
        val finalizer = source("RecordingFinalizerWorker.kt")
        val staleBranch = service.substringAfter("RecordingIdentityPolicy.Action.ENQUEUE_EXACT_PENDING -> {")
            .substringBefore("RecordingIdentityPolicy.Action.IGNORE")

        assertTrue(service.contains("RecordingIdentityPolicy.handleStop("))
        assertTrue(finalizer.contains("RecordingIdentityPolicy.recoverPending("))
        assertTrue(finalizer.contains("prefs.getString(RecordingService.KEY_CURRENT_PATH, null)"))
        assertTrue(finalizer.contains("prefs.getString(RecordingService.KEY_CLIENT_ID, null)"))
        assertTrue(staleBranch.contains("enqueueFinalizer(exactPending.path!!, exactPending.clientId!!"))
        assertFalse(staleBranch.contains("stopRecording()"))
        assertFalse(staleBranch.contains("putBoolean(KEY_IS_RECORDING, false)"))
    }

    @Test fun finalizationPendingBlocksStartUntilExactClearNotifiesUi() {
        val activity = source("MainActivity.kt")
        val service = source("RecordingService.kt")
        val finalizer = source("RecordingFinalizerWorker.kt")
        val start = activity.substringAfter("private fun startRecordingIfReady()")
            .substringBefore("private fun startRecordingStatusTicker")
        val serviceStart = service.substringAfter("ACTION_START -> {").substringBefore("ACTION_STOP -> {")
        val clear = finalizer.substringAfter("private fun clearPending(context")

        assertTrue(start.contains("getBoolean(RecordingService.KEY_FINALIZATION_PENDING, false)"))
        assertTrue(serviceStart.contains("RecordingIdentityPolicy.canStart("))
        assertTrue(serviceStart.contains("getBoolean(KEY_IS_RECORDING, false)"))
        assertTrue(serviceStart.contains("getBoolean(KEY_FINALIZATION_PENDING, false)"))
        assertTrue(serviceStart.contains("recorderAlreadyActive = recorder != null"))
        assertFalse(serviceStart.contains("UUID.randomUUID()"))
        assertTrue(clear.contains("KEY_FINALIZATION_PATH"))
        assertTrue(clear.contains("KEY_FINALIZATION_CLIENT_ID"))
        assertTrue(finalizer.contains("ACTION_FINALIZATION_COMPLETED"))
        assertTrue(activity.contains("addAction(RecordingService.ACTION_FINALIZATION_COMPLETED)"))
    }

    @Test fun publicationAndExactClearUseOneFinalizationIdentityFence() {
        val activity = source("MainActivity.kt")
        val service = source("RecordingService.kt")
        val finalizer = source("RecordingFinalizerWorker.kt")
        val publish = service.substringAfter("fun persistFinalizationIntent").substringBefore("override fun onCreate")
        val clear = finalizer.substringAfter("private fun clearPending(context")

        assertTrue(activity.contains("RecordingService.persistFinalizationIntent"))
        assertTrue(publish.contains("FinalizationIdentityLock.withLock"))
        assertTrue(publish.contains("FinalizationIdentityPolicy.canPublish"))
        assertTrue(clear.contains("FinalizationIdentityLock.withLock"))
        assertTrue(clear.contains("FinalizationIdentityPolicy.canClear"))
    }
}
