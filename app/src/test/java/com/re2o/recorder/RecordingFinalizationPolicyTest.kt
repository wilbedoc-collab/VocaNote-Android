package com.re2o.recorder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFinalizationPolicyTest {
    @Test fun malformedFileRetriesOnlyWithinBound() {
        assertEquals(
            RecordingFinalizationPolicy.InvalidFileAction.RETRY,
            RecordingFinalizationPolicy.invalidFileAction(runAttemptCount = 0)
        )
        assertEquals(
            RecordingFinalizationPolicy.InvalidFileAction.TERMINAL_FAILURE,
            RecordingFinalizationPolicy.invalidFileAction(
                runAttemptCount = RecordingFinalizationPolicy.MAX_INVALID_FILE_RETRIES
            )
        )
    }

    @Test fun stableRecognizedHeaderStillRequiresPlayableAudio() {
        val file = File.createTempFile("finalized_audio_", ".m4a")
        try {
            val bytes = ByteArray(4096)
            "ftyp".toByteArray().copyInto(bytes, destinationOffset = 4)
            file.writeBytes(bytes)

            assertTrue(LocalAudioFileValidator.isRecognizedAudio(file))
            assertFalse(FinalizedAudioValidator.isValid(file) { false })
            assertTrue(FinalizedAudioValidator.isValid(file) { true })
        } finally {
            file.delete()
        }
    }

    @Test fun missingRecoveryTargetIsAnExactTerminalFailure() {
        assertEquals(
            RecordingFinalizationPolicy.RecoveryAction.TERMINAL_FAILURE,
            RecordingFinalizationPolicy.recoveryAction(fileExists = false)
        )
        assertEquals(
            RecordingFinalizationPolicy.RecoveryAction.ENQUEUE_EXACT_PENDING,
            RecordingFinalizationPolicy.recoveryAction(fileExists = true)
        )
    }

    @Test fun existingRowShortCircuitRequiresExactExistingFile() {
        assertTrue(RecordingFinalizationPolicy.canResumeExisting("/audio/A.m4a", "/audio/A.m4a", true))
        assertFalse(RecordingFinalizationPolicy.canResumeExisting("/audio/A.m4a", "/audio/A.m4a", false))
        assertFalse(RecordingFinalizationPolicy.canResumeExisting("/audio/A.m4a", "/audio/B.m4a", true))
    }

    @Test fun forgottenIdentityCanNeverBeRegisteredAgain() {
        assertTrue(RecordingPersistencePolicy.canRegisterSaved(forgotten = false))
        assertFalse(RecordingPersistencePolicy.canRegisterSaved(forgotten = true))
    }
}
