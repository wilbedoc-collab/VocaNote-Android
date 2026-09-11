package com.re2o.recorder

import android.content.Context

/** Server-authoritative lease bridge for existing uploaded recordings. */
object EditLeaseCoordinator {
    private const val OWNER = "android"
    private const val TTL_SECONDS = 60

    fun acquire(context: Context, recordingId: String): LocalEditStateStore.EditState {
        return try {
            val lease = VocaNoteApiClient.acquireEditLease(
                BuildConfig.VOCANOTE_SERVER_URL, BuildConfig.VOCANOTE_UPLOAD_TOKEN,
                recordingId, OWNER, TTL_SECONDS
            )
            LocalEditStateStore.adoptServerLease(
                context, recordingId, lease.generation, lease.token, OWNER, TTL_SECONDS * 1000L
            )
        } catch (e: Exception) {
            LocalEditStateStore.requireRecovery(context, recordingId)
            throw e
        }
    }

    fun heartbeat(context: Context, recordingId: String): Boolean {
        val local = LocalEditStateStore.get(context, recordingId) ?: return false
        val token = local.leaseToken ?: return false
        return try {
            val remote = VocaNoteApiClient.heartbeatEditLease(
                BuildConfig.VOCANOTE_SERVER_URL, BuildConfig.VOCANOTE_UPLOAD_TOKEN,
                recordingId, token, TTL_SECONDS
            )
            remote && LocalEditStateStore.heartbeat(context, recordingId, token, TTL_SECONDS * 1000L)
        } catch (_: Exception) {
            LocalEditStateStore.requireRecovery(context, recordingId)
            false
        }
    }

    fun release(context: Context, recordingId: String): Boolean {
        val local = LocalEditStateStore.get(context, recordingId) ?: return false
        val token = local.leaseToken ?: return false
        return try {
            VocaNoteApiClient.releaseEditLease(
                BuildConfig.VOCANOTE_SERVER_URL, BuildConfig.VOCANOTE_UPLOAD_TOKEN,
                recordingId, token
            )
            LocalEditStateStore.remove(context, recordingId)
            true
        } catch (_: Exception) {
            LocalEditStateStore.requireRecovery(context, recordingId)
            false
        }
    }
}
