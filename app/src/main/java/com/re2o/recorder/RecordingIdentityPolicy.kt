package com.re2o.recorder

/** Pure identity fence for STOP and crash-recovery decisions. */
object RecordingIdentityPolicy {
    data class Identity(val path: String?, val clientId: String?) {
        fun isComplete(): Boolean = !path.isNullOrBlank() && !clientId.isNullOrBlank()
    }

    enum class Action { STOP_EXACT_CURRENT, ENQUEUE_EXACT_PENDING, IGNORE }
    enum class StartOutcome { WAIT, STARTED, FAILED }

    fun startOutcome(
        requested: Identity,
        durableCurrent: Identity,
        durableIsRecording: Boolean,
        failed: Identity?
    ): StartOutcome {
        if (!requested.isComplete()) return StartOutcome.WAIT
        if (durableIsRecording && durableCurrent.isComplete() && durableCurrent == requested) {
            return StartOutcome.STARTED
        }
        if (!durableIsRecording && failed?.isComplete() == true && failed == requested) {
            return StartOutcome.FAILED
        }
        return StartOutcome.WAIT
    }

    fun canStart(
        durableIsRecording: Boolean,
        finalizationPending: Boolean,
        recorderAlreadyActive: Boolean
    ): Boolean = !durableIsRecording && !finalizationPending && !recorderAlreadyActive

    fun canStart(
        requested: Identity,
        durablePending: Identity,
        durableIsRecording: Boolean,
        finalizationPending: Boolean,
        recorderAlreadyActive: Boolean
    ): Boolean = requested.isComplete() && durablePending.isComplete() && requested == durablePending && canStart(
        durableIsRecording = durableIsRecording,
        finalizationPending = finalizationPending,
        recorderAlreadyActive = recorderAlreadyActive
    )

    fun recoverPending(
        pending: Identity,
        current: Identity,
        durableIsRecording: Boolean
    ): Action {
        if (!pending.isComplete()) return Action.IGNORE
        return if (durableIsRecording && current.isComplete() && current == pending) {
            Action.STOP_EXACT_CURRENT
        } else {
            Action.ENQUEUE_EXACT_PENDING
        }
    }

    fun handleStop(
        requested: Identity?,
        pending: Identity?,
        current: Identity,
        durableIsRecording: Boolean
    ): Action {
        if (requested == null) {
            return if (durableIsRecording && current.isComplete()) {
                Action.STOP_EXACT_CURRENT
            } else {
                Action.IGNORE
            }
        }
        val target = requested
        if (!target.isComplete()) return Action.IGNORE
        if (durableIsRecording && current.isComplete() && target == current) {
            return Action.STOP_EXACT_CURRENT
        }
        if (pending?.isComplete() == true && target == pending) {
            return Action.ENQUEUE_EXACT_PENDING
        }
        return Action.IGNORE
    }
}
