package com.re2o.recorder

/** Bounds malformed-file finalization retries without discarding evidence. */
object RecordingFinalizationPolicy {
    const val MAX_INVALID_FILE_RETRIES = 5

    enum class InvalidFileAction { RETRY, TERMINAL_FAILURE }
    enum class RecoveryAction { ENQUEUE_EXACT_PENDING, TERMINAL_FAILURE }

    fun invalidFileAction(runAttemptCount: Int): InvalidFileAction =
        if (runAttemptCount < MAX_INVALID_FILE_RETRIES) {
            InvalidFileAction.RETRY
        } else {
            InvalidFileAction.TERMINAL_FAILURE
        }

    fun recoveryAction(fileExists: Boolean): RecoveryAction =
        if (fileExists) RecoveryAction.ENQUEUE_EXACT_PENDING else RecoveryAction.TERMINAL_FAILURE

    fun canResumeExisting(pendingPath: String, existingPath: String, fileExists: Boolean): Boolean =
        fileExists && pendingPath.isNotBlank() && pendingPath == existingPath
}
