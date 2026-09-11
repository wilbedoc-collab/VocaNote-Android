package com.re2o.recorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable, row-based ownership for local canonical audio generations. */
object LocalAudioReferenceStore {
    private const val PREFS = "vocanote_audio_references"
    private const val KEY_REFS = "refs"
    private const val KEY_ACKED_PURGES = "acked_purge_ids"

    data class AudioRef(val recordingId: String, val generation: Int, val path: String)

    @Synchronized
    private fun load(context: Context): MutableList<AudioRef> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REFS, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNullTo(mutableListOf()) { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNullTo null
            val rid = o.optString("recording_id")
            val path = o.optString("path")
            if (rid.isBlank() || path.isBlank()) null else AudioRef(rid, o.optInt("generation", 1), path)
        }
    }

    @Synchronized
    private fun encode(refs: List<AudioRef>): String {
        val arr = JSONArray()
        refs.forEach { r -> arr.put(JSONObject().put("recording_id", r.recordingId).put("generation", r.generation).put("path", r.path)) }
        return arr.toString()
    }

    @Synchronized
    private fun save(context: Context, refs: List<AudioRef>) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REFS, encode(refs)).commit())
    }

    fun register(context: Context, recordingId: String, generation: Int, path: String) {
        DeletionPurgeOperationLock.withLock {
            synchronized(this) {
                val refs = load(context)
                if (refs.none { it.recordingId == recordingId && it.generation == generation && it.path == path }) {
                    refs.add(AudioRef(recordingId, generation, path))
                    save(context, refs)
                }
            }
        }
    }

    @Synchronized
    fun pathsForServerId(context: Context, recordingId: String): List<String> =
        load(context).filter { it.recordingId == recordingId }.map { it.path }.distinct()

    @Synchronized
    fun hasOtherOwner(context: Context, recordingId: String, path: String): Boolean =
        load(context).any { it.recordingId != recordingId && it.path == path }

    /** Called only after every target audio path has verified absent. */
    @Synchronized
    fun removeForServerId(context: Context, recordingId: String): Boolean {
        val refs = load(context)
        val kept = refs.filterNot { it.recordingId == recordingId }
        if (kept.size != refs.size) save(context, kept)
        return true
    }

    fun applyPurge(context: Context, command: VocaNoteApiClient.PurgeCommand): Boolean {
        if (!PurgeDeletionPolicy.isValidGeneration(command.generation)) return false
        // Legacy discovery must finish before the reference-store monitor is entered.
        val legacyPaths = PurgeDeletionPolicy.legacyInitialUploadFallback(
            command.generation,
            LocalRecordingStore.load(context)
                .filter { it.serverRecordingId == command.recordingId }
                .map { it.localAudioPath }
        )
        return DeletionPurgeOperationLock.withLock {
            synchronized(this) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val acked = prefs.getStringSet(KEY_ACKED_PURGES, emptySet())?.toMutableSet() ?: mutableSetOf()
                if (command.purgeId in acked) return@synchronized true

                var currentRefs: List<AudioRef> = load(context)
                var targets = currentRefs.filter {
                    it.recordingId == command.recordingId && it.generation == command.generation
                }
                if (targets.isEmpty()) {
                    // A LocalRecordingStore server-id mapping proves only the initial upload.
                    // Never alias a later purge generation to an older canonical audio path.
                    val migrated = legacyPaths?.map { AudioRef(command.recordingId, 1, it) }
                        ?: return@synchronized false
                    currentRefs = (currentRefs + migrated).distinct()
                    targets = migrated
                }
                val unsharedTargets = targets.filter { target -> currentRefs.none { it != target && it.path == target.path } }
                val existing = unsharedTargets.map { it.path }.filter { File(it).exists() }.toMutableSet()
                val deleted = PurgeDeletionPolicy.deleteAndVerify(unsharedTargets.map { it.path }, existing) { path ->
                    val file = File(path)
                    val ok = !file.exists() || file.delete()
                    if (ok && !file.exists()) existing.remove(path)
                    ok
                }
                if (!deleted) return@synchronized false
                // Shared paths are proven retained by another durable live row; only
                // this old-generation ownership row is removed.
                acked.add(command.purgeId)
                prefs.edit()
                    .putString(KEY_REFS, encode(currentRefs - targets.toSet()))
                    .putStringSet(KEY_ACKED_PURGES, acked)
                    .commit()
            }
        }
    }
}
