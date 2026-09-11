package com.re2o.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PurgeDeletionPolicyTest {
    @Test fun purgeGenerationMustBeStrictlyPositiveJsonInteger() {
        assertNull(PurgeDeletionPolicy.parseGeneration(null))
        assertNull(PurgeDeletionPolicy.parseGeneration(0))
        assertNull(PurgeDeletionPolicy.parseGeneration(-1))
        assertNull(PurgeDeletionPolicy.parseGeneration("1"))
        assertNull(PurgeDeletionPolicy.parseGeneration(1.0))
        assertNull(PurgeDeletionPolicy.parseGeneration(1.5))
        assertNull(PurgeDeletionPolicy.parseGeneration(Long.MAX_VALUE))
        assertEquals(1, PurgeDeletionPolicy.parseGeneration(1))
        assertEquals(Int.MAX_VALUE, PurgeDeletionPolicy.parseGeneration(Int.MAX_VALUE.toLong()))
    }

    @Test fun alreadyAbsentIsIdempotentSuccess() {
        assertTrue(PurgeDeletionPolicy.deleteAndVerify(listOf("a"), emptySet()) { true })
    }

    @Test fun noTargetGenerationTwoPreservesGenerationOneCanonicalAudio() {
        val canonical = mutableSetOf("generation-1.m4a")

        assertNull(PurgeDeletionPolicy.legacyInitialUploadFallback(2, canonical.toList()))
        assertTrue("generation-1.m4a" in canonical)
    }

    @Test fun legacyInitialUploadMappingIsAcceptedOnlyForGenerationOne() {
        assertEquals(
            listOf("generation-1.m4a"),
            PurgeDeletionPolicy.legacyInitialUploadFallback(1, listOf("generation-1.m4a"))
        )
        assertNull(PurgeDeletionPolicy.legacyInitialUploadFallback(Int.MAX_VALUE, listOf("generation-1.m4a")))
        assertNull(PurgeDeletionPolicy.legacyInitialUploadFallback(1, emptyList()))
    }

    @Test fun physicalDeleteBeforeReceiptCommitRetriesFromRetainedReference() {
        val existing = mutableSetOf("canonical.m4a")
        val knownReference = listOf("canonical.m4a")

        assertTrue(PurgeDeletionPolicy.deleteAndVerify(knownReference, existing) { path ->
            existing.remove(path)
        })
        // Simulate process death before the reference + receipt transaction commits.
        assertTrue(PurgeDeletionPolicy.deleteAndVerify(knownReference, existing) { false })
    }

    @Test fun refsRemovalAndLocalAckUseOneSharedPreferencesTransaction() {
        val store = File("src/main/java/com/re2o/recorder/LocalAudioReferenceStore.kt").readText()
        val purgeFlow = store.substringAfter("fun applyPurge")

        val transaction = purgeFlow.substringAfter("prefs.edit()")
            .substringBefore(".commit()")

        assertTrue(transaction.contains("putString(KEY_REFS"))
        assertTrue(transaction.contains("putStringSet(KEY_ACKED_PURGES"))
        assertEquals(1, Regex("prefs\\.edit\\(\\)").findAll(purgeFlow).count())
        assertEquals(1, Regex("\\.commit\\(\\)").findAll(purgeFlow).count())
        assertTrue(purgeFlow.indexOf("if (!deleted)") < purgeFlow.indexOf("prefs.edit()"))
        assertFalse(purgeFlow.contains("save(context"))
    }

    @Test fun everyKnownPathMustBeAbsent() {
        val existing = mutableSetOf("a", "b")
        assertTrue(PurgeDeletionPolicy.deleteAndVerify(listOf("a", "b"), existing) { path ->
            existing.remove(path)
        })
        assertTrue(existing.isEmpty())
    }

    @Test fun existingFileDeletionFailurePreventsAck() {
        val existing = mutableSetOf("a")
        assertFalse(PurgeDeletionPolicy.deleteAndVerify(listOf("a"), existing) { false })
        assertTrue("a" in existing)
    }

    @Test fun pathStillPresentAfterReportedDeletePreventsAck() {
        val existing = mutableSetOf("a")
        assertFalse(PurgeDeletionPolicy.deleteAndVerify(listOf("a"), existing) { true })
    }

    @Test fun protectedSharedPathPreventsAckWhilePresent() {
        val existing = mutableSetOf("shared")
        assertFalse(PurgeDeletionPolicy.deleteAndVerify(listOf("shared"), existing, setOf("shared")) { path ->
            existing.remove(path)
        })
        assertTrue("shared" in existing)
    }
}
