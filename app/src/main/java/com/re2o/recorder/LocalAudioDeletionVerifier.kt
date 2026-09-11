package com.re2o.recorder

import java.io.File

object LocalAudioDeletionVerifier {
    fun deleteAndVerify(file: File): Boolean = try {
        deleteAndVerify(exists = file::exists, delete = file::delete)
    } catch (_: Exception) {
        false
    }

    fun deleteAndVerify(exists: () -> Boolean, delete: () -> Boolean): Boolean {
        if (!exists()) return true
        if (!delete()) return false
        return !exists()
    }

    fun deleteThenCommit(
        exists: () -> Boolean,
        delete: () -> Boolean,
        commitForgotten: () -> Unit
    ): Boolean {
        if (!deleteAndVerify(exists, delete)) return false
        commitForgotten()
        return true
    }

    fun deleteAllThenCommit(
        paths: List<String>,
        exists: (String) -> Boolean,
        delete: (String) -> Boolean,
        commit: () -> Unit
    ): Boolean {
        return try {
            for (path in paths.distinct()) {
                if (!deleteAndVerify(exists = { exists(path) }, delete = { delete(path) })) return false
            }
            commit()
            true
        } catch (_: Exception) {
            false
        }
    }
}
