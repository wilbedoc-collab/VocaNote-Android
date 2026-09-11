package com.re2o.recorder

/** Pure lifecycle decisions shared by the local store and upload worker. */
object UploadLifecyclePolicy {
    enum class ForgetDecision {
        DELETE_LOCALLY,
        DEFER_UPLOAD_IN_FLIGHT,
        REQUIRE_SERVER_DELETE
    }

    fun forgetDecision(uploadStatus: String, serverRecordingId: String?): ForgetDecision = when {
        !serverRecordingId.isNullOrBlank() -> ForgetDecision.REQUIRE_SERVER_DELETE
        uploadStatus == LocalRecordingStore.STATUS_UPLOADING ||
            uploadStatus == LocalRecordingStore.STATUS_UPLOAD_AUTH_PROBE_PENDING ->
            ForgetDecision.DEFER_UPLOAD_IN_FLIGHT
        else -> ForgetDecision.DELETE_LOCALLY
    }
}

object UploadFailurePolicy {
    enum class Disposition {
        RETRY,
        TERMINAL,
        AUTH_BLOCKED
    }

    fun classifyHttp(code: Int): Disposition = when (code) {
        401, 403 -> Disposition.AUTH_BLOCKED
        400, 404, 405, 409, 413, 415, 422 -> Disposition.TERMINAL
        else -> Disposition.RETRY
    }

    fun canResetAuthBlocked(status: String, controlled: Boolean): Boolean =
        controlled && status == LocalRecordingStore.STATUS_UPLOAD_AUTH_BLOCKED

    fun shouldEnqueueNext(status: String?): Boolean =
        status != LocalRecordingStore.STATUS_UPLOAD_AUTH_BLOCKED
}
