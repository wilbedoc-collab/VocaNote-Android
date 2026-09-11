package com.re2o.recorder

/** Pure tombstone gate used inside the shared deletion/registration lock. */
object RecordingPersistencePolicy {
    fun canRegisterSaved(forgotten: Boolean): Boolean = !forgotten
}
