package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioDeletionVerifierTest {
    @Test fun absentFileIsConfirmedWithoutDelete() {
        val events = mutableListOf<String>()

        val confirmed = LocalAudioDeletionVerifier.deleteAndVerify(
            exists = { events += "exists"; false },
            delete = { events += "delete"; true }
        )

        assertTrue(confirmed)
        assertEquals(listOf("exists"), events)
    }

    @Test fun successfulDeleteMustBeFollowedByConfirmedAbsence() {
        val events = mutableListOf<String>()
        var exists = true

        val confirmed = LocalAudioDeletionVerifier.deleteAndVerify(
            exists = { events += "exists"; exists },
            delete = { events += "delete"; exists = false; true }
        )

        assertTrue(confirmed)
        assertEquals(listOf("exists", "delete", "exists"), events)
    }

    @Test fun failedDeleteIsNotConfirmed() {
        val events = mutableListOf<String>()

        val confirmed = LocalAudioDeletionVerifier.deleteAndVerify(
            exists = { events += "exists"; true },
            delete = { events += "delete"; false }
        )

        assertFalse(confirmed)
        assertEquals(listOf("exists", "delete"), events)
    }

    @Test fun successfulDeleteResultIsRejectedWhenFileStillExists() {
        val events = mutableListOf<String>()

        val confirmed = LocalAudioDeletionVerifier.deleteAndVerify(
            exists = { events += "exists"; true },
            delete = { events += "delete"; true }
        )

        assertFalse(confirmed)
        assertEquals(listOf("exists", "delete", "exists"), events)
    }

    @Test fun durableCommitRunsOnlyAfterDeletionIsConfirmed() {
        val events = mutableListOf<String>()
        var exists = true

        val committed = LocalAudioDeletionVerifier.deleteThenCommit(
            exists = { events += "exists"; exists },
            delete = { events += "delete"; exists = false; true },
            commitForgotten = { events += "commit" }
        )

        assertTrue(committed)
        assertEquals(listOf("exists", "delete", "exists", "commit"), events)
    }

    @Test fun durableCommitIsSkippedWhenDeletionCannotBeConfirmed() {
        val events = mutableListOf<String>()

        val committed = LocalAudioDeletionVerifier.deleteThenCommit(
            exists = { events += "exists"; true },
            delete = { events += "delete"; false },
            commitForgotten = { events += "commit" }
        )

        assertFalse(committed)
        assertEquals(listOf("exists", "delete"), events)
    }

    @Test fun multiFileCommitRunsOnlyAfterEveryExactPathIsAbsent() {
        val events = mutableListOf<String>()
        val existing = mutableSetOf("a", "b")

        val committed = LocalAudioDeletionVerifier.deleteAllThenCommit(
            paths = listOf("a", "b"),
            exists = { path -> events += "exists:$path"; path in existing },
            delete = { path -> events += "delete:$path"; existing.remove(path); true },
            commit = { events += "commit" }
        )

        assertTrue(committed)
        assertEquals(
            listOf("exists:a", "delete:a", "exists:a", "exists:b", "delete:b", "exists:b", "commit"),
            events
        )
    }

    @Test fun multiFileFailurePreservesDurableRowBySkippingCommit() {
        val events = mutableListOf<String>()

        val committed = LocalAudioDeletionVerifier.deleteAllThenCommit(
            paths = listOf("a", "b"),
            exists = { path -> events += "exists:$path"; true },
            delete = { path -> events += "delete:$path"; path == "a" },
            commit = { events += "commit" }
        )

        assertFalse(committed)
        assertFalse(events.contains("commit"))
    }
}
