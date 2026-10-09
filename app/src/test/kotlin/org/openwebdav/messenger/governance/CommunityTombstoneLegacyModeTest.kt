package org.openwebdav.messenger.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityTombstoneLegacyModeTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun host_legacy_reference_is_scoped_to_exact_chat_and_cannot_be_replayed_as_origin_hash() {
        val host = crypto.generateIdentity()
        val hash = LegacyChatOriginReference.contentAddress(COMMUNITY_ID, CHAT_ID)
        val record =
            CommunityTombstone(
                COMMUNITY_ID,
                CHAT_ID,
                hash,
                TombstoneScope.GROUP,
                1,
                TombstoneSignerRole.PINNED_HOST,
                host.copySignPublic(),
                emptyList(),
                TombstoneOriginMode.LEGACY_CHAT_ID,
            )
        val bytes = CommunityTombstoneCodec.sign(record, host.copySignSecret(), crypto)

        assertEquals(
            TombstoneOriginMode.LEGACY_CHAT_ID,
            CommunityTombstoneCodec.verify(
                bytes,
                COMMUNITY_ID,
                CHAT_ID,
                hash,
                TombstoneScope.GROUP,
                TombstoneOriginMode.LEGACY_CHAT_ID,
                crypto,
            )?.originMode,
        )
        assertNull(
            CommunityTombstoneCodec.verify(
                bytes,
                COMMUNITY_ID,
                CHAT_ID,
                hash,
                TombstoneScope.GROUP,
                TombstoneOriginMode.ORIGIN_HASH,
                crypto,
            ),
        )
        assertEquals(hash, LegacyChatOriginReference.contentAddress(COMMUNITY_ID, CHAT_ID))
        assertNull(
            CommunityTombstoneCodec.verify(
                bytes,
                COMMUNITY_ID,
                "other-chat",
                hash,
                TombstoneScope.GROUP,
                TombstoneOriginMode.LEGACY_CHAT_ID,
                crypto,
            ),
        )
    }

    private companion object {
        const val COMMUNITY_ID = "community-1"
        const val CHAT_ID = "legacy-chat"
    }
}
