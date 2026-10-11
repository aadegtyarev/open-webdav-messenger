package org.openwebdav.messenger.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityTombstoneCodecTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun group_tombstone_signature_is_bound_to_scope_and_origin() {
        val creator = crypto.generateIdentity()
        val record =
            CommunityTombstone(
                "community-1",
                "abcdefghijklmnopqrstuvwxyz234567",
                "a".repeat(32),
                TombstoneScope.GROUP,
                1,
                TombstoneSignerRole.GROUP_CREATOR,
                creator.copySignPublic(),
                emptyList(),
            )
        val bytes = CommunityTombstoneCodec.sign(record, creator.copySignSecret(), crypto)

        assertEquals(
            TombstoneSignerRole.GROUP_CREATOR,
            CommunityTombstoneCodec.verify(
                bytes,
                record.communityId,
                record.chatId,
                record.originHash,
                record.scope,
                record.originMode,
                crypto,
            )?.signerRole,
        )
        assertNull(
            CommunityTombstoneCodec.verify(
                bytes,
                record.communityId,
                record.chatId,
                record.originHash,
                TombstoneScope.DM,
                record.originMode,
                crypto,
            ),
        )
        assertNull(
            CommunityTombstoneCodec.verify(
                bytes,
                record.communityId,
                record.chatId,
                "b".repeat(32),
                record.scope,
                record.originMode,
                crypto,
            ),
        )
        val tampered = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(
            CommunityTombstoneCodec.verify(
                tampered,
                record.communityId,
                record.chatId,
                record.originHash,
                record.scope,
                record.originMode,
                crypto,
            ),
        )
    }
}
