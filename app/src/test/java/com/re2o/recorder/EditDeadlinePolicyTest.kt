package com.re2o.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class EditDeadlinePolicyTest {
    @Test fun pendingBeforeDeadlineWaits() {
        assertEquals(
            EditDeadlinePolicy.Action.WAIT,
            EditDeadlinePolicy.action("EDIT_PENDING", 10_000L, null, 9_999L)
        )
    }

    @Test fun pendingAtDeadlineConfirms() {
        assertEquals(
            EditDeadlinePolicy.Action.AUTO_CONFIRM,
            EditDeadlinePolicy.action("EDIT_PENDING", 10_000L, null, 10_000L)
        )
    }

    @Test fun activeLeaseBlocksDeadline() {
        assertEquals(
            EditDeadlinePolicy.Action.BLOCKED_BY_LEASE,
            EditDeadlinePolicy.action("EDITING", 1L, 20_000L, 10_000L)
        )
    }

    @Test fun expiredLeaseRequiresRecoveryInsteadOfConfirming() {
        assertEquals(
            EditDeadlinePolicy.Action.RECOVERY_REQUIRED,
            EditDeadlinePolicy.action("EDITING", 1L, 10_000L, 10_000L)
        )
    }

    @Test fun confirmedStateResumesUploadRegistrationAfterCrash() {
        assertEquals(
            EditDeadlinePolicy.Action.REGISTER_UPLOAD,
            EditDeadlinePolicy.action("CONFIRMED", 1L, null, 20_000L)
        )
    }
}
