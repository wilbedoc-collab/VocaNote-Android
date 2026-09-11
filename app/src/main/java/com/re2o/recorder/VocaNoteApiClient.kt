package com.re2o.recorder

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

object VocaNoteApiClient {
    private const val MAX_RESPONSE_BYTES = 1024 * 1024

    data class PurgeCommand(val purgeId: String, val recordingId: String, val generation: Int) {
        init {
            require(purgeId.isNotBlank()) { "purgeId must not be blank" }
            require(recordingId.isNotBlank()) { "recordingId must not be blank" }
            require(PurgeDeletionPolicy.isValidGeneration(generation)) { "generation must be positive" }
        }
    }
    data class EditLease(val token: String, val state: String, val generation: Int)

    data class DeleteRecordingResult(
        val ok: Boolean,
        val recordingId: String,
        val deleteState: String,
        val deleteScope: String
    )

    class HttpStatusException(val code: Int, body: String) : IOException("HTTP $code $body")

    fun deleteRecording(serverUrl: String, token: String, recordingId: String): DeleteRecordingResult {
        return withConnection("$serverUrl/api/recordings/${urlEncode(recordingId)}") { conn ->
            conn.requestMethod = "DELETE"
            configure(conn, token)
            val (code, body) = readResponse(conn)
            if (code !in 200..299) throw HttpStatusException(code, body)
            val root = JSONObject(body)
            DeleteRecordingResult(
                ok = root.optBoolean("ok", false),
                recordingId = root.optString("recording_id", ""),
                deleteState = root.optString("delete_state", ""),
                deleteScope = root.optString("delete_scope", "") // future-proof: accept unknown values without throwing
            )
        }
    }

    fun pendingAndroidPurges(serverUrl: String, token: String): List<PurgeCommand> {
        val root = JSONObject(httpGet("$serverUrl/api/purges/android-pending", token))
        val arr = root.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val generation = PurgeDeletionPolicy.parseGeneration(o.opt("generation"))
                ?: throw IOException("Malformed purge generation at items[$i]")
            val purgeId = o.optString("purge_id")
            val recordingId = o.optString("recording_id")
            if (purgeId.isBlank() || recordingId.isBlank()) null
            else PurgeCommand(purgeId, recordingId, generation)
        }
    }

    fun acknowledgeAndroidPurge(serverUrl: String, token: String, purgeId: String): Boolean {
        return withConnection("$serverUrl/api/purges/${urlEncode(purgeId)}/android-ack") { conn ->
            conn.requestMethod = "POST"
            conn.doOutput = true
            configure(conn, token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val (code, body) = readResponse(conn)
            if (code !in 200..299) throw HttpStatusException(code, body)
            JSONObject(body).optBoolean("ok", false)
        }
    }

    fun acquireEditLease(serverUrl: String, token: String, recordingId: String, owner: String, ttlSeconds: Int = 60): EditLease {
        val root = postJson("$serverUrl/api/recordings/${urlEncode(recordingId)}/edit-lease/acquire", token,
            JSONObject().put("owner", owner).put("ttl_seconds", ttlSeconds))
        val rec = root.getJSONObject("recording")
        return EditLease(root.getString("lease_token"), rec.getString("state"), rec.getInt("current_generation"))
    }

    fun heartbeatEditLease(serverUrl: String, token: String, recordingId: String, leaseToken: String, ttlSeconds: Int = 60): Boolean =
        postJson("$serverUrl/api/recordings/${urlEncode(recordingId)}/edit-lease/heartbeat", token,
            JSONObject().put("lease_token", leaseToken).put("ttl_seconds", ttlSeconds)).optBoolean("ok", false)

    fun releaseEditLease(serverUrl: String, token: String, recordingId: String, leaseToken: String): JSONObject =
        postJson("$serverUrl/api/recordings/${urlEncode(recordingId)}/edit-lease/release", token,
            JSONObject().put("lease_token", leaseToken)).getJSONObject("recording")

    private fun postJson(url: String, token: String, payload: JSONObject): JSONObject {
        return withConnection(url) { conn ->
            conn.requestMethod = "POST"
            conn.doOutput = true
            configure(conn, token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val (code, body) = readResponse(conn)
            if (code !in 200..299) throw HttpStatusException(code, body)
            JSONObject(body)
        }
    }

    fun httpGet(url: String, token: String): String {
        return withConnection(url) { conn ->
            conn.requestMethod = "GET"
            configure(conn, token)
            val (code, body) = readResponse(conn)
            if (code !in 200..299) throw HttpStatusException(code, body)
            body
        }
    }

    private fun configure(conn: HttpURLConnection, token: String) {
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("X-Upload-Token", token)
    }

    private fun <T> withConnection(url: String, block: (HttpURLConnection) -> T): T =
        useConnection(URL(url).openConnection() as HttpURLConnection, block)

    internal fun <T> useConnection(conn: HttpURLConnection, block: (HttpURLConnection) -> T): T {
        return try {
            block(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return code to readResponseBounded(stream, MAX_RESPONSE_BYTES)
    }

    internal fun readResponseBounded(stream: InputStream?, maxBytes: Int): String {
        require(maxBytes >= 0)
        if (stream == null) return ""
        return stream.use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("HTTP response exceeds $maxBytes bytes")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    fun isNetworkAmbiguous(e: Exception): Boolean = e is SocketTimeoutException

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
