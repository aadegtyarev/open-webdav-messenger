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

    @Test
    fun dm_tombstone_binds_initiator_and_both_participants() {
        val initiator = crypto.generateIdentity()
        val peer = crypto.generateIdentity()
        val participants = listOf(participant(initiator), participant(peer))
        val record =
            CommunityTombstone(
                "community-1",
                "abcdefghijklmnopqrstuvwxyz234567",
                "c".repeat(32),
                TombstoneScope.DM,
                1,
                TombstoneSignerRole.DM_INITIATOR,
                initiator.copySignPublic(),
                participants,
            )
        val bytes = CommunityTombstoneCodec.sign(record, initiator.copySignSecret(), crypto)

        assertEquals(
            participants.size,
            CommunityTombstoneCodec.verify(
                bytes,
                record.communityId,
                record.chatId,
                record.originHash,
                record.scope,
                record.originMode,
                crypto,
            )?.participants?.size,
        )
        val unrelatedSigner = crypto.generateIdentity()
        val invalid = record.copy(signerSigningPublicKey = unrelatedSigner.copySignPublic())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CommunityTombstoneCodec.sign(invalid, initiator.copySignSecret(), crypto)
        }
    }

    private fun participant(identity: org.openwebdav.messenger.identity.Identity) =
        CommunityDmParticipant(identity.copySignPublic(), identity.copyBoxPublic())
}
