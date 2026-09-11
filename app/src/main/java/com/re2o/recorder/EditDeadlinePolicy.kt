package com.re2o.recorder

/** Pure policy shared by durable WorkManager execution and JVM tests. */
object EditDeadlinePolicy {
    enum class Action { WAIT, AUTO_CONFIRM, BLOCKED_BY_LEASE, RECOVERY_REQUIRED, REGISTER_UPLOAD, NO_ACTION }

    fun action(state: String, graceDeadlineMs: Long, leaseExpiresAtMs: Long?, nowMs: Long): Action = when {
        state == LocalEditStateStore.EDITING && (leaseExpiresAtMs ?: 0L) > nowMs -> Action.BLOCKED_BY_LEASE
        state == LocalEditStateStore.EDITING -> Action.RECOVERY_REQUIRED
        state == LocalEditStateStore.EDIT_PENDING && graceDeadlineMs <= nowMs -> Action.AUTO_CONFIRM
        state == LocalEditStateStore.EDIT_PENDING -> Action.WAIT
        state == LocalEditStateStore.CONFIRMED -> Action.REGISTER_UPLOAD
        else -> Action.NO_ACTION
    }
}
