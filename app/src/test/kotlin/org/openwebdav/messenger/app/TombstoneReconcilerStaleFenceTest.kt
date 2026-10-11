package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityTombstone
import org.openwebdav.messenger.governance.CommunityTombstoneChatScanner
import org.openwebdav.messenger.governance.TombstoneOriginMode
import org.openwebdav.messenger.governance.TombstoneScope
import org.openwebdav.messenger.governance.TombstoneSignerRole

class TombstoneReconcilerStaleFenceTest {
    @Test
    fun accountSwitchAfterScanFencesLocalApply() =
        runTest {
            var current = true
            var applied = false
            val reconciler =
                TombstoneReconciler(
                    isCurrent = { current },
                    scan = {
                        current = false
                        candidate()
                    },
                    authorize = { true },
                    apply = {
                        applied = true
                        true
                    },
                )
            assertEquals(TombstoneReconciler.Result.Stale, reconciler.reconcile())
            assertFalse(applied)
        }

    private fun candidate() =
        CommunityTombstoneChatScanner.Result.Found(
            CommunityTombstone(
                "c", "abcdefghijklmnopqrstuvwxyz234567", "a".repeat(32), TombstoneScope.GROUP,
                1, TombstoneSignerRole.PINNED_HOST, byteArrayOf(1), emptyList(),
                originMode = TombstoneOriginMode.LEGACY_CHAT_ID,
            ),
            "b".repeat(32),
            byteArrayOf(1),
            byteArrayOf(2),
        )
}
