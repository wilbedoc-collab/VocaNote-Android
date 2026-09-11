package com.re2o.recorder

/** Serializes durable pending-START identity publication and consumption. */
object RecordingStartIdentityLock {
    private val monitor = Any()

    fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
