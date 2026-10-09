package org.openwebdav.messenger.governance

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityTombstoneAuthorizationTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun creator_needs_exact_verified_origin_while_pinned_host_does_not() {
        val creator = crypto.generateIdentity()
        val origin =
            CommunityGroupOrigin(
                "community-1",
                CHAT_ID,
                org.openwebdav.messenger.chatdirectory.ChatAccess.PUBLIC,
                creator.copySignPublic(),
                creator.copyBoxPublic(),
                ByteArray(32),
            )
        val hash = "a".repeat(32)
        val creatorDelete = record(TombstoneSignerRole.GROUP_CREATOR, creator.copySignPublic(), TombstoneScope.GROUP, emptyList(), hash)

        assertNotNull(CommunityTombstoneAuthorization.group(creatorDelete, origin, hash, null))
        assertNull(CommunityTombstoneAuthorization.group(creatorDelete, null, null, null))
        assertNull(CommunityTombstoneAuthorization.group(creatorDelete, origin, "b".repeat(32), null))

        val host = crypto.generateIdentity()
        val hostDelete = record(TombstoneSignerRole.PINNED_HOST, host.copySignPublic(), TombstoneScope.GROUP, emptyList(), hash)
        val pin = CommunityTrustRecord("community-1", hash, byteArrayOf(), host.copySignPublic(), host.copyBoxPublic(), true, 0, null)
        assertNotNull(CommunityTombstoneAuthorization.group(hostDelete, null, null, pin))
        assertNull(CommunityTombstoneAuthorization.group(hostDelete, null, null, pin.copy(fingerprintVerified = false)))
    }

    @Test
    fun dm_delete_requires_the_initiator_and_exact_participant_pair() {
        val initiator = crypto.generateIdentity()
        val peer = crypto.generateIdentity()
        val participants = CommunityDmOriginKeyOrder.sort(listOf(participant(initiator), participant(peer)))
        val origin = CommunityDmOrigin("community-1", CHAT_ID, participants, initiator.copySignPublic(), ByteArray(32))
        val tombstone =
            record(TombstoneSignerRole.DM_INITIATOR, initiator.copySignPublic(), TombstoneScope.DM, participants, "c".repeat(32))

        assertNotNull(CommunityTombstoneAuthorization.dm(tombstone, origin, "c".repeat(32)))
        assertNull(CommunityTombstoneAuthorization.dm(tombstone, origin, "d".repeat(32)))
        val other = crypto.generateIdentity()
        val changed = origin.copy(participants = CommunityDmOriginKeyOrder.sort(listOf(participant(initiator), participant(other))))
        assertNull(CommunityTombstoneAuthorization.dm(tombstone, changed, "c".repeat(32)))
    }

    private fun participant(identity: org.openwebdav.messenger.identity.Identity) =
        CommunityDmParticipant(identity.copySignPublic(), identity.copyBoxPublic())

    private fun record(
        role: TombstoneSignerRole,
        signer: ByteArray,
        scope: TombstoneScope,
        participants: List<CommunityDmParticipant>,
        originHash: String,
    ) = CommunityTombstone("community-1", CHAT_ID, originHash, scope, 1, role, signer, participants)

    private companion object {
        const val CHAT_ID = "abcdefghijklmnopqrstuvwxyz234567"
    }
}
