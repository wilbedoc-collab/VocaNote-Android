package com.re2o.recorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable edit state used by finalization, grace expiry and editor entry. */
object LocalEditStateStore {
    private const val PREFS = "vocanote_edit_states"
    private const val KEY_ITEMS = "items"
    const val EDIT_PENDING = "EDIT_PENDING"
    const val EDITING = "EDITING"
    const val EDIT_RECOVERY_REQUIRED = "EDIT_RECOVERY_REQUIRED"
    const val CONFIRMED = "CONFIRMED"

    data class EditState(
        val recordingId: String,
        val generation: Int,
        val state: String,
        val preEditState: String,
        val graceDeadlineMs: Long,
        val leaseToken: String?,
        val leaseOwner: String?,
        val leaseAcquiredAtMs: Long?,
        val leaseExpiresAtMs: Long?,
        val leaseHeartbeatMs: Long?
    )

    @Synchronized
    fun enterPending(context: Context, recordingId: String, generation: Int, preEditState: String, nowMs: Long = System.currentTimeMillis()): EditState {
        val state = EditState(recordingId, generation, EDIT_PENDING, preEditState,
            nowMs + BuildConfig.VOCANOTE_EDIT_GRACE_SECONDS * 1000L, null, null, null, null, null)
        put(context, state)
        return state
    }

    @Synchronized
    fun acquireLease(context: Context, recordingId: String, owner: String, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): EditState? {
        val cur = get(context, recordingId) ?: return null
        if (cur.state !in setOf(EDIT_PENDING, EDIT_RECOVERY_REQUIRED)) return null
        val next = cur.copy(state = EDITING, leaseToken = UUID.randomUUID().toString(), leaseOwner = owner,
            leaseAcquiredAtMs = nowMs, leaseExpiresAtMs = nowMs + ttlMs, leaseHeartbeatMs = nowMs)
        put(context, next)
        return next
    }

    @Synchronized
    fun adoptServerLease(context: Context, recordingId: String, generation: Int, token: String, owner: String, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): EditState {
        val next = EditState(recordingId, generation, EDITING, "FINAL", nowMs, token, owner, nowMs, nowMs + ttlMs, nowMs)
        put(context, next)
        return next
    }

    @Synchronized
    fun requireRecovery(context: Context, recordingId: String) {
        val cur = get(context, recordingId)
        if (cur == null) {
            val now = System.currentTimeMillis()
            put(context, EditState(recordingId, 1, EDIT_RECOVERY_REQUIRED, "FINAL", now, null, null, null, null, null))
        } else {
            put(context, cur.copy(state = EDIT_RECOVERY_REQUIRED))
        }
    }

    @Synchronized
    fun heartbeat(context: Context, recordingId: String, token: String, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cur = get(context, recordingId) ?: return false
        if (cur.state != EDITING || cur.leaseToken != token || (cur.leaseExpiresAtMs ?: 0L) <= nowMs) return false
        put(context, cur.copy(leaseHeartbeatMs = nowMs, leaseExpiresAtMs = nowMs + ttlMs))
        return true
    }

    /** Returns BLOCKED, AUTO_CONFIRMED, RECOVERY_REQUIRED, REGISTER_UPLOAD, or NO_ACTION. */
    @Synchronized
    fun evaluateDeadline(context: Context, recordingId: String, nowMs: Long = System.currentTimeMillis()): String {
        val cur = get(context, recordingId) ?: return "NO_ACTION"
        return when (EditDeadlinePolicy.action(cur.state, cur.graceDeadlineMs, cur.leaseExpiresAtMs, nowMs)) {
            EditDeadlinePolicy.Action.BLOCKED_BY_LEASE -> "BLOCKED"
            EditDeadlinePolicy.Action.RECOVERY_REQUIRED -> {
                put(context, cur.copy(state = EDIT_RECOVERY_REQUIRED)); "RECOVERY_REQUIRED"
            }
            EditDeadlinePolicy.Action.AUTO_CONFIRM -> {
                put(context, cur.copy(state = CONFIRMED)); "AUTO_CONFIRMED"
            }
            EditDeadlinePolicy.Action.REGISTER_UPLOAD -> "REGISTER_UPLOAD"
            else -> "NO_ACTION"
        }
    }

    @Synchronized
    fun get(context: Context, recordingId: String): EditState? = load(context).firstOrNull { it.recordingId == recordingId }

    @Synchronized
    fun pending(context: Context): List<EditState> = load(context).filter { it.state in setOf(EDIT_PENDING, CONFIRMED) }

    @Synchronized
    fun remove(context: Context, recordingId: String) {
        val items = load(context).filterNot { it.recordingId == recordingId }
        val arr = JSONArray(); items.forEach { arr.put(toJson(it)) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, arr.toString()).commit())
    }

    private fun put(context: Context, item: EditState) {
        val items = load(context)
        val index = items.indexOfFirst { it.recordingId == item.recordingId }
        if (index >= 0) items[index] = item else items.add(item)
        val arr = JSONArray()
        items.forEach { s -> arr.put(toJson(s)) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, arr.toString()).commit())
    }

    private fun load(context: Context): MutableList<EditState> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNullTo(mutableListOf()) { i -> fromJson(arr.optJSONObject(i)) }
    }

    private fun fromJson(o: JSONObject?): EditState? {
        o ?: return null
        val rid = o.optString("recording_id")
        if (rid.isBlank()) return null
        return EditState(rid, o.optInt("generation", 1), o.optString("state", EDIT_PENDING),
            o.optString("pre_edit_state", "STOPPED"), o.optLong("grace_deadline_ms"),
            o.optString("lease_token").ifBlank { null }, o.optString("lease_owner").ifBlank { null },
            o.optLong("lease_acquired_at_ms").takeIf { it > 0 }, o.optLong("lease_expires_at_ms").takeIf { it > 0 },
            o.optLong("lease_heartbeat_ms").takeIf { it > 0 })
    }

    private fun toJson(s: EditState): JSONObject = JSONObject()
        .put("recording_id", s.recordingId).put("generation", s.generation).put("state", s.state)
        .put("pre_edit_state", s.preEditState).put("grace_deadline_ms", s.graceDeadlineMs)
        .put("lease_token", s.leaseToken ?: "").put("lease_owner", s.leaseOwner ?: "")
        .put("lease_acquired_at_ms", s.leaseAcquiredAtMs ?: 0L).put("lease_expires_at_ms", s.leaseExpiresAtMs ?: 0L)
        .put("lease_heartbeat_ms", s.leaseHeartbeatMs ?: 0L)
}
