package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionWiringContractTest {
    private fun source(relativePath: String): String =
        File("src/main/java/com/re2o/recorder/$relativePath").readText()

    @Test fun workManagerProductionUploadPublishesExactStreamingProgress() {
        val worker = source("VocaNoteUploadWorker.kt")

        assertTrue(worker.contains("setProgressAsync(uploadProgressData(sent, total))"))
        assertTrue(worker.contains("onProgress = onProgress"))
        assertTrue(worker.contains("putLong(KEY_PROGRESS_SENT_BYTES, sent)"))
        assertTrue(worker.contains("putLong(KEY_PROGRESS_TOTAL_BYTES, total)"))
        assertTrue(worker.contains("putInt(KEY_PROGRESS_PERCENT, percent)"))
    }

    @Test fun progressPayloadPreservesExactByteCountsAndComputesPercent() {
        val progress = VocaNoteUploadWorker.uploadProgressData(sent = 25L, total = 100L)

        assertEquals(25L, progress.getLong(VocaNoteUploadWorker.KEY_PROGRESS_SENT_BYTES, -1L))
        assertEquals(100L, progress.getLong(VocaNoteUploadWorker.KEY_PROGRESS_TOTAL_BYTES, -1L))
        assertEquals(25, progress.getInt(VocaNoteUploadWorker.KEY_PROGRESS_PERCENT, -1))
    }

    @Test fun ambiguousDeleteNeverUsesGenericListAbsenceAsSuccessProof() {
        val activity = source("MainActivity.kt")
        val client = source("VocaNoteApiClient.kt")
        val deleteFlow = activity.substringAfter("private fun deleteRecording(recordingId: String)")
            .substringBefore("private fun applyDetailJson")

        assertFalse(deleteFlow.contains("recordingExistsInServerList"))
        assertFalse(client.contains("fun recordingExistsInServerList"))
        assertTrue(deleteFlow.contains("catch (e: java.net.SocketTimeoutException)"))
        assertTrue(deleteFlow.contains("success = false"))
    }

    @Test fun localDeleteIsOwnedByAtomicallyFencedStoreTransaction() {
        val activity = source("MainActivity.kt")
        val store = source("LocalRecordingStore.kt")
        val localDeleteFlow = activity.substringAfter("if (recordingId.startsWith(\"local:\"))")
            .substringBefore("thread {")
        val forgetFlow = store.substringAfter("fun tryForgetLocal")
            .substringBefore("fun claimForUpload")

        assertFalse(localDeleteFlow.contains("File(forget.localAudioPath).delete()"))
        assertTrue(forgetFlow.contains("LocalAudioDeletionVerifier.deleteThenCommit"))
        assertTrue(forgetFlow.contains("DeletionPurgeOperationLock.withLock"))
        assertTrue(forgetFlow.contains("synchronized(this)"))
        assertTrue(store.contains("@Synchronized\n    fun claimForUpload"))
    }

    @Test fun durableAuthProbeCannotBeForgottenOrRemovedDuringUploadOrCancellation() {
        val policy = source("UploadLifecyclePolicy.kt")
        val store = source("LocalRecordingStore.kt")
        val worker = source("VocaNoteUploadWorker.kt")
        val forgetPolicy = policy.substringAfter("fun forgetDecision").substringBefore("else ->")
        val markForgotten = store.substringAfter("fun markForgotten").substringBefore("fun isForgotten")
        val tryForgetLocal = store.substringAfter("fun tryForgetLocal").substringBefore("fun claimForUpload")
        val removeByVisibleId = store.substringAfter("fun removeByVisibleId").substringBefore("fun cleanupAfterServerDelete")

        assertTrue(forgetPolicy.contains("STATUS_UPLOAD_AUTH_PROBE_PENDING"))
        listOf(markForgotten, tryForgetLocal, removeByVisibleId).forEach { removalPath ->
            assertTrue(removalPath.contains("UploadLifecyclePolicy.forgetDecision"))
            assertTrue(removalPath.contains("UploadLifecyclePolicy.ForgetDecision.DELETE_LOCALLY"))
        }
        assertTrue(markForgotten.contains("DeletionPurgeOperationLock.withLock"))
        assertTrue(tryForgetLocal.contains("DeletionPurgeOperationLock.withLock"))
        assertTrue(store.contains("@Synchronized\n    fun removeByVisibleId"))

        val preUploadCancellation = worker.substringAfter("if (!shouldContinue())")
            .substringBefore("else uploadEligible")
        val uploadCancellation = worker.substringAfter("catch (e: Exception)")
            .substringBefore("private fun multipartUpload")
        assertTrue(preUploadCancellation.contains("STATUS_UPLOAD_AUTH_PROBE_PENDING"))
        assertTrue(uploadCancellation.contains("keepCircuitAfterProbe"))
        assertTrue(uploadCancellation.contains("STATUS_UPLOAD_AUTH_PROBE_PENDING"))
        assertFalse(preUploadCancellation.contains("markForgotten"))
        assertFalse(uploadCancellation.contains("markForgotten"))
    }

    @Test fun serviceClearsDurableRecordingOnlyAfterShutdownAndFinalizerEnqueue() {
        val service = source("RecordingService.kt")
        val stopFlow = service.substringAfter("ACTION_STOP -> {").substringBefore("stopForeground")
        val persistAt = stopFlow.indexOf("persistFinalizationIntent")
        val stopAt = stopFlow.indexOf("stopRecording()")
        val enqueueAt = stopFlow.indexOf("enqueueFinalizer(path, clientId, startedAt, stoppedAt)")
        val clearAt = stopFlow.indexOf("putBoolean(KEY_IS_RECORDING, false)")
        val broadcastAt = stopFlow.indexOf("sendBroadcast(Intent(ACTION_RECORDING_STOPPED)")

        assertTrue(persistAt >= 0)
        assertTrue(stopAt > persistAt)
        assertTrue(enqueueAt > stopAt)
        assertTrue(clearAt > enqueueAt)
        assertTrue(broadcastAt > clearAt)
        val finalizer = source("RecordingFinalizerWorker.kt")
        assertTrue(finalizer.contains("workName(clientId)"))
        assertTrue(finalizer.contains("ExistingWorkPolicy.KEEP"))
    }

    @Test fun activityPersistsAndPassesExactStopIntentBeforeDispatchingStop() {
        val activity = source("MainActivity.kt")
        val stopFlow = activity.substringAfter("private fun stopRecordingAndUpload()")
            .substringBefore("private fun fetchLatestResult")
        val persistAt = stopFlow.indexOf("RecordingService.persistFinalizationIntent")
        val dispatchAt = stopFlow.indexOf("startService(svc)")

        assertTrue(persistAt >= 0)
        assertTrue(dispatchAt > persistAt)
        assertTrue(stopFlow.contains("putExtra(RecordingService.EXTRA_PATH, path)"))
        assertTrue(stopFlow.contains("putExtra(RecordingService.EXTRA_CLIENT_ID, clientId)"))
        assertTrue(stopFlow.contains("putExtra(RecordingService.EXTRA_STARTED_AT_MS, startedAt)"))
        assertTrue(stopFlow.contains("putExtra(RecordingService.EXTRA_STOPPED_AT_MS, stoppedAt)"))
        assertFalse(stopFlow.contains("newestLocalAudioFile"))
        assertFalse(stopFlow.contains("saveRecordingState(path, false)"))
        assertFalse(stopFlow.contains("putBoolean(KEY_IS_RECORDING, false)"))
        assertTrue(stopFlow.contains("isStopping = true"))
        assertTrue(stopFlow.contains("showStoppingStatus()"))
    }

    @Test fun successfulServerDeleteRequiresVerifiedLocalCleanupBeforeUiSuccess() {
        val activity = source("MainActivity.kt")
        val deleteFlow = activity.substringAfter("private fun deleteRecording(recordingId: String)")
            .substringBefore("private fun applyDetailJson")
        val store = source("LocalRecordingStore.kt")
        val cleanupFlow = store.substringAfter("fun cleanupAfterServerDelete")
            .substringBefore("fun localPathForVisibleId")

        assertTrue(deleteFlow.contains("LocalRecordingStore.cleanupAfterServerDelete(this, recordingId)"))
        assertFalse(deleteFlow.contains("LocalRecordingStore.removeByServerId(this, recordingId)"))
        assertFalse(store.contains("@Synchronized\n    fun cleanupAfterServerDelete"))
        assertTrue(cleanupFlow.contains("DeletionPurgeOperationLock.withLock"))
        assertTrue(cleanupFlow.contains("LocalAudioDeletionVerifier.deleteAllThenCommit"))
        assertTrue(cleanupFlow.contains("LocalAudioReferenceStore.removeForServerId"))
        assertTrue(cleanupFlow.indexOf("LocalAudioReferenceStore.removeForServerId") < cleanupFlow.indexOf("putString(KEY_ITEMS"))
    }

    @Test fun deletionAndPurgeNeverAcquireRecordingAndReferenceStoreMonitorsInOppositeOrder() {
        val recordingStore = source("LocalRecordingStore.kt")
        val cleanupFlow = recordingStore.substringAfter("fun cleanupAfterServerDelete")
            .substringBefore("fun localPathForVisibleId")
        val referenceStore = source("LocalAudioReferenceStore.kt")
        val purgeFlow = referenceStore.substringAfter("fun applyPurge")

        assertTrue(cleanupFlow.contains("DeletionPurgeOperationLock.withLock"))
        assertTrue(purgeFlow.contains("DeletionPurgeOperationLock.withLock"))
        assertTrue(cleanupFlow.indexOf("synchronized(this)") > cleanupFlow.indexOf("LocalAudioReferenceStore.removeForServerId"))
        assertTrue(purgeFlow.indexOf("LocalRecordingStore.load(context)") < purgeFlow.indexOf("synchronized(this)"))
        val referenceCriticalSection = purgeFlow.substringAfter("synchronized(this)")
        assertFalse(referenceCriticalSection.contains("LocalRecordingStore."))
    }

    @Test fun startupReconstructsOnlyPersistedFinalizerAndLegacyNewestFileFallbackIsGone() {
        val activity = source("MainActivity.kt")
        val finalizer = source("RecordingFinalizerWorker.kt")

        assertTrue(activity.substringAfter("override fun onCreate").substringBefore("override fun onNewIntent")
            .contains("RecordingFinalizerWorker.recoverPending(this)"))
        assertTrue(finalizer.contains("KEY_FINALIZATION_PENDING"))
        assertTrue(finalizer.contains("LocalRecordingStore.get(context, clientId)"))
        assertTrue(finalizer.contains("File(path)"))
        assertFalse(activity.contains("newestLocalAudioFile"))
        assertFalse(activity.contains("candidateRecordingFile"))
        assertFalse(activity.contains("private fun multipartUpload"))
    }

    @Test fun authBlockedHasBoundedStartupAndManualRecoveryWithoutWorkerHotRetry() {
        val activity = source("MainActivity.kt")
        val store = source("LocalRecordingStore.kt")
        val worker = source("VocaNoteUploadWorker.kt")

        assertTrue(activity.contains("recoverOneAuthBlockedForProcessStart"))
        assertTrue(activity.contains("업로드 다시 시도"))
        assertTrue(store.contains("processStartAuthRecoveryAttempted"))
        assertTrue(store.contains("requestManualUploadRetry"))
        val authCatch = worker.substringAfter("UploadFailurePolicy.Disposition.AUTH_BLOCKED")
            .substringBefore("UploadFailurePolicy.Disposition.RETRY")
        assertFalse(authCatch.contains("return false"))
    }

    @Test fun queueWideAuthCircuitGatesEnqueueClaimAndWorkerHandoff() {
        val worker = source("VocaNoteUploadWorker.kt")
        val store = source("LocalRecordingStore.kt")
        val doWork = worker.substringAfter("override fun doWork(): Result {")
            .substringBefore("companion object")

        assertTrue(store.contains("STATUS_UPLOAD_AUTH_PROBE_PENDING"))
        assertTrue(store.substringAfter("fun claimForUpload").substringBefore("fun recoveryCandidates")
            .contains("UploadAuthCircuitPolicy.canClaim"))
        assertTrue(worker.substringAfter("fun enqueue(context").substringBefore("fun enqueueControlledProbe")
            .contains("LocalRecordingStore.canAutomaticallyEnqueue"))
        assertTrue(doWork.contains("LocalRecordingStore.shouldEnqueueNext"))
        assertTrue(doWork.contains("enqueueNext(applicationContext, clientId)"))
        assertTrue(worker.contains("fun enqueueControlledProbe"))
        assertTrue(store.contains("releaseCircuitAfterProbe"))
        assertTrue(store.contains("keepCircuitAfterProbe"))
    }

    @Test fun processStartResumesOneDurableProbeWithoutVolatileOnlyRelease() {
        val store = source("LocalRecordingStore.kt")
        val recovery = store.substringAfter("fun recoverOneAuthBlockedForProcessStart")
            .substringBefore("fun requestManualUploadRetry")

        assertTrue(recovery.contains("prepareControlledAuthProbe"))
        assertTrue(recovery.contains("enqueueControlledProbe"))
        assertTrue(store.contains("STATUS_UPLOAD_AUTH_PROBE_PENDING"))
        assertTrue(store.contains("processStartAuthRecoveryAttempted"))
        assertTrue(store.contains("selectControlledRetry"))
        assertTrue(store.contains("preferredClientId = clientId"))
    }

    @Test fun controlledRecoveryAtomicallyRetiresInvalidProbeBeforePromotingReplacement() {
        val store = source("LocalRecordingStore.kt")
        val prepare = store.substringAfter("private fun prepareControlledAuthProbe")
            .substringBefore("fun releaseCircuitAfterProbe")
        val normalizeAt = prepare.indexOf("normalizeAuthProbes(initialCandidates)")
        val selectAt = prepare.indexOf("selectControlledRetry(candidates, trigger)")
        val promoteAt = prepare.indexOf("uploadStatus = STATUS_UPLOAD_AUTH_PROBE_PENDING")

        assertTrue(normalizeAt >= 0)
        assertTrue(selectAt > normalizeAt)
        assertTrue(promoteAt > selectAt)
        assertTrue(prepare.contains("uploadStatus = retirement.status"))
        assertTrue(prepare.contains("lastError = retirement.error"))
        assertTrue(prepare.contains("items.count { it.uploadStatus == STATUS_UPLOAD_AUTH_PROBE_PENDING } == 1"))
        assertFalse(prepare.contains("removeAt("))
        assertFalse(prepare.contains("filterNot"))
    }
}
