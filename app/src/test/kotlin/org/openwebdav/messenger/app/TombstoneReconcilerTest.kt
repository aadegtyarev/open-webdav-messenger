package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityTombstone
import org.openwebdav.messenger.governance.CommunityTombstoneChatScanner
import org.openwebdav.messenger.governance.TombstoneOriginMode
import org.openwebdav.messenger.governance.TombstoneScope
import org.openwebdav.messenger.governance.TombstoneSignerRole

class TombstoneReconcilerTest {
    @Test
    fun unavailableScanCanRetryAndApplyAfterReconnect() =
        runTest {
            var attempts = 0
            var applied = false
            val reconciler =
                TombstoneReconciler(
                    isCurrent = { true },
                    scan = {
                        attempts++
                        if (attempts == 1) CommunityTombstoneChatScanner.Result.Unavailable else candidate()
                    },
                    authorize = { true },
                    apply = {
                        applied = true
                        true
                    },
                )
            assertEquals(TombstoneReconciler.Result.Unavailable, reconciler.reconcile())
            assertFalse(applied)
            assertEquals(TombstoneReconciler.Result.Applied, reconciler.reconcile())
            assertTrue(applied)
        }

    @Test
    fun conflictAndUnauthorizedCandidatesNeverApply() =
        runTest {
            var applied = false
            val conflict =
                reconciler(scan = { CommunityTombstoneChatScanner.Result.Conflict }, apply = {
                    applied = true
                    true
                })
            val unauthorized =
                reconciler(scan = { candidate() }, authorize = { false }, apply = {
                    applied = true
                    true
                })
            assertEquals(TombstoneReconciler.Result.Conflict, conflict.reconcile())
            assertEquals(TombstoneReconciler.Result.Unauthorized, unauthorized.reconcile())
            assertFalse(applied)
        }

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

    private fun reconciler(
        scan: suspend () -> CommunityTombstoneChatScanner.Result,
        authorize: suspend (CommunityTombstoneChatScanner.Result.Found) -> Boolean = { true },
        apply: suspend (CommunityTombstoneChatScanner.Result.Found) -> Boolean,
    ) = TombstoneReconciler({ true }, scan, authorize, apply)

    private fun candidate() =
        CommunityTombstoneChatScanner.Result.Found(
            CommunityTombstone(
                communityId = "c",
                chatId = "abcdefghijklmnopqrstuvwxyz234567",
                originHash = "a".repeat(32),
                scope = TombstoneScope.GROUP,
                revision = 1,
                signerRole = TombstoneSignerRole.PINNED_HOST,
                signerSigningPublicKey = byteArrayOf(1),
                participants = emptyList(),
                originMode = TombstoneOriginMode.LEGACY_CHAT_ID,
            ),
            "b".repeat(32),
            byteArrayOf(1),
            byteArrayOf(2),
        )
}
