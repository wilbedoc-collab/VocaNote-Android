package com.re2o.recorder

/** Serializes all durable finalization identity compare-and-commit operations. */
object FinalizationIdentityLock {
    private val monitor = Any()

    fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
