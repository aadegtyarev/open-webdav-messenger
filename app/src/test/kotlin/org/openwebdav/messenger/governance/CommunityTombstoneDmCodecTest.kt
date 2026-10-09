package org.openwebdav.messenger.governance

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityTombstoneDmCodecTest {
    private val crypto = IdentityTestSupport.identityCrypto()

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
