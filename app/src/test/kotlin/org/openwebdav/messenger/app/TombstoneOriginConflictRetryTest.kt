package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityTombstone
import org.openwebdav.messenger.governance.CommunityTombstoneAuthorization
import org.openwebdav.messenger.governance.CommunityTombstoneChatScanner
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.governance.LegacyChatOriginReference
import org.openwebdav.messenger.governance.TombstoneOriginMode
import org.openwebdav.messenger.governance.TombstoneScope
import org.openwebdav.messenger.governance.TombstoneSignerRole
import org.openwebdav.messenger.identity.IdentityTestSupport

class TombstoneOriginConflictRetryTest {
    @Test
    fun pinned_host_legacy_tombstone_applies_on_peer_and_restart_retry() =
        runTest {
            val crypto = IdentityTestSupport.identityCrypto()
            val host = crypto.generateIdentity()
            val chatId = "abcdefghijklmnopqrstuvwxyz234567"
            val record =
                CommunityTombstone(
                    "community", chatId, LegacyChatOriginReference.contentAddress("community", chatId),
                    TombstoneScope.GROUP, 1, TombstoneSignerRole.PINNED_HOST, host.copySignPublic(), emptyList(),
                    TombstoneOriginMode.LEGACY_CHAT_ID,
                )
            val pin =
                CommunityTrustRecord(
                    "community",
                    "z".repeat(32),
                    byteArrayOf(1),
                    host.copySignPublic(),
                    host.copyBoxPublic(),
                    true,
                    0,
                    null,
                )
            val found =
                CommunityTombstoneChatScanner.Result.Found(
                    record,
                    "a".repeat(32),
                    byteArrayOf(1),
                    byteArrayOf(2),
                )
            var deletedLocally = false

            fun reconciler() =
                TombstoneReconciler(
                    isCurrent = { true },
                    scan = { found },
                    authorize = { candidate ->
                        assertNotNull(CommunityTombstoneAuthorization.groupUnderOriginConflict(candidate.record, pin))
                        true
                    },
                    apply = {
                        deletedLocally = true
                        true
                    },
                )

            assertEquals(TombstoneReconciler.Result.Applied, reconciler().reconcile())
            assertTrue(deletedLocally)
            assertEquals(TombstoneReconciler.Result.Applied, reconciler().reconcile())
            assertTrue(deletedLocally)
        }
}
