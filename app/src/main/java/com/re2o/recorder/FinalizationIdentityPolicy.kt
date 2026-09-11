package com.re2o.recorder

/** Pure rules for publishing and clearing the single durable finalization identity. */
object FinalizationIdentityPolicy {
    data class Identity(val path: String?, val clientId: String?) {
        fun isComplete(): Boolean = !path.isNullOrBlank() && !clientId.isNullOrBlank()
    }

    fun canPublish(
        storedPending: Identity?,
        requested: Identity,
        current: Identity,
        durableIsRecording: Boolean
    ): Boolean {
        if (!requested.isComplete()) return false
        if (durableIsRecording && (!current.isComplete() || current != requested)) return false
        return storedPending?.takeIf { it.isComplete() }?.let { it == requested } ?: true
    }

    fun canClear(storedPending: Identity?, expected: Identity): Boolean =
        expected.isComplete() && storedPending?.isComplete() == true && storedPending == expected
}
