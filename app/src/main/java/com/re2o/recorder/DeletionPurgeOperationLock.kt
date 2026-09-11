package com.re2o.recorder

/**
 * Serializes cross-store deletion operations without nesting the recording-store
 * and reference-store monitors.
 */
object DeletionPurgeOperationLock {
    private val monitor = Any()

    fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}