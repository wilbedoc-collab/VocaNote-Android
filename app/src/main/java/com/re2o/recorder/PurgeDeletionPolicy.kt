package com.re2o.recorder

/** Deletion may be acknowledged only after every owned unprotected path is absent. */
object PurgeDeletionPolicy {
    /** Accept only positive JSON integer values that fit the local generation type. */
    fun parseGeneration(value: Any?): Int? {
        val generation = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        return generation.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    fun isValidGeneration(generation: Int): Boolean = generation > 0

    /** LocalRecordingStore server IDs describe only the initial uploaded generation. */
    fun legacyInitialUploadFallback(generation: Int, mappedPaths: List<String>): List<String>? =
        mappedPaths.filter { it.isNotBlank() }.distinct()
            .takeIf { generation == 1 && it.isNotEmpty() }

    fun deleteAndVerify(
        knownPaths: List<String>,
        existingPaths: Set<String>,
        protectedPaths: Set<String> = emptySet(),
        delete: (String) -> Boolean
    ): Boolean {
        for (path in knownPaths.distinct()) {
            if (path in protectedPaths && path in existingPaths) return false
            if (path in existingPaths) {
                if (!delete(path)) return false
                if (path in existingPaths) return false
            }
        }
        return true
    }
}
