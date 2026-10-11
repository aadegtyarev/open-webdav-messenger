package org.openwebdav.messenger.export

import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.governance.CommunityDmOrigin
import org.openwebdav.messenger.governance.CommunityDmOriginCodec
import org.openwebdav.messenger.governance.CommunityDmParticipant
import org.openwebdav.messenger.governance.CommunityGovernanceEnvelopeCodec
import org.openwebdav.messenger.governance.CommunityTombstone
import org.openwebdav.messenger.governance.CommunityTombstoneCodec
import org.openwebdav.messenger.governance.GovernanceObjectKind
import org.openwebdav.messenger.governance.TombstoneScope
import org.openwebdav.messenger.governance.TombstoneSignerRole
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import java.util.Base64

internal class AccountBackupDmGraveyardFixture {
    private val native = CryptoTestSupport.native()
    private val crypto = IdentityCrypto(native)
    val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 17 })
    private val envelopes = CommunityGovernanceEnvelopeCodec(Aead(native))

    fun isAuthenticated(
        deletion: ChatDeletionBackup,
        expectedOwner: CommunityDmParticipant? = null,
        communityId: String = "community-a",
    ): Boolean =
        AccountBackupGraveyardValidation.hasAuthenticatedTombstones(
            communityId,
            null,
            listOf(deletion),
            envelopes,
            crypto,
            expectedOwner,
        )

    fun deletion(
        owner: Identity,
        initiator: Identity = owner,
        wrongPeer: Boolean = false,
    ): ChatDeletionBackup {
        val ownerParticipant = participant(owner)
        val signer = initiator.copySignPublic()
        val peer =
            if (ownerParticipant.signingPublicKey.contentEquals(
                    signer,
                )
            ) {
                participant(crypto.generateIdentity())
            } else {
                participant(initiator)
            }
        val peerForOrigin = if (wrongPeer) participant(crypto.generateIdentity()) else peer
        val participants = listOf(ownerParticipant, peer)
        val originBytes =
            sign(initiator) { secret ->
                CommunityDmOriginCodec.sign(
                    CommunityDmOrigin("community-a", "deleted-dm", participants, signer, ByteArray(32) { 9 }),
                    secret,
                    crypto,
                )
            }
        val tombstone =
            CommunityTombstone(
                "community-a",
                "deleted-dm",
                CommunityDmOriginCodec.contentAddress(originBytes),
                TombstoneScope.DM,
                1,
                TombstoneSignerRole.DM_INITIATOR,
                signer,
                listOf(ownerParticipant, peerForOrigin),
            )
        val signed = sign(initiator) { secret -> CommunityTombstoneCodec.sign(tombstone, secret, crypto) }
        val sealed = envelopes.seal(GovernanceObjectKind.DM_TOMBSTONE, "community-a", key, signed)
        return ChatDeletionBackup(
            "deleted-dm", "DM", "ORIGIN_HASH", CommunityDmOriginCodec.contentAddress(originBytes),
            CommunityTombstoneCodec.contentAddress(signed), Base64.getEncoder().encodeToString(sealed),
            "COMPLETE", 0, null, null, 123L,
            Base64.getEncoder().encodeToString(signed), Base64.getEncoder().encodeToString(originBytes),
        )
    }

    fun participant(identity: Identity) = CommunityDmParticipant(identity.copySignPublic(), identity.copyBoxPublic())

    private fun sign(
        identity: Identity,
        create: (ByteArray) -> ByteArray,
    ): ByteArray {
        val secret = identity.copySignSecret()
        return try {
            create(secret)
        } finally {
            secret.fill(0)
        }
    }
}
