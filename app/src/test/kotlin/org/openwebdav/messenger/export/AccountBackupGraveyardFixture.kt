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
import org.openwebdav.messenger.transport.ConnectionConfig
import java.util.Base64

internal class AccountBackupGraveyardFixture {
    private val native = CryptoTestSupport.native()
    private val crypto = IdentityCrypto(native)
    val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 17 })
    private val envelopes = CommunityGovernanceEnvelopeCodec(Aead(native))

    fun isAuthenticated(
        deletion: ChatDeletionBackup,
        communityId: String = "community-a",
        communityKey: ChatKey? = key,
        expectedSigner: ByteArray? = null,
    ): Boolean =
        AccountBackupGraveyardValidation.hasAuthenticatedTombstones(
            communityId,
            communityKey,
            listOf(deletion),
            envelopes,
            crypto,
            expectedSigner,
        )

    fun tamperCiphertext(deletion: ChatDeletionBackup): ChatDeletionBackup {
        val bytes = Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64)
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
        return deletion.copy(tombstoneStoredBytesBase64 = Base64.getEncoder().encodeToString(bytes))
    }

    fun deletion(): ChatDeletionBackup {
        val identity = crypto.generateIdentity()
        val secret = identity.copySignSecret()
        val signed =
            try {
                CommunityTombstoneCodec.sign(
                    CommunityTombstone(
                        communityId = "community-a",
                        chatId = "deleted-group",
                        originHash = "a".repeat(32),
                        scope = TombstoneScope.GROUP,
                        revision = 1,
                        signerRole = TombstoneSignerRole.PINNED_HOST,
                        signerSigningPublicKey = identity.copySignPublic(),
                        participants = emptyList(),
                    ),
                    secret,
                    crypto,
                )
            } finally {
                secret.fill(0)
            }
        val sealed = envelopes.seal(GovernanceObjectKind.GROUP_TOMBSTONE, "community-a", key, signed)
        return ChatDeletionBackup(
            "deleted-group",
            "GROUP",
            "ORIGIN_HASH",
            "a".repeat(32),
            CommunityTombstoneCodec.contentAddress(signed),
            Base64.getEncoder().encodeToString(sealed),
            "CLEANUP_PENDING",
            0,
            null,
            null,
            123L,
        )
    }

    fun dmDeletion(
        identity: Identity,
        wrongPeer: Boolean = false,
    ): ChatDeletionBackup {
        val signer = identity.copySignPublic()
        val signerBox = identity.copyBoxPublic()
        val peer = crypto.generateIdentity()
        val otherPeer = crypto.generateIdentity()
        val participants =
            listOf(
                CommunityDmParticipant(signer, signerBox),
                CommunityDmParticipant(peer.copySignPublic(), peer.copyBoxPublic()),
            )
        val secret = identity.copySignSecret()
        val originBytes =
            try {
                CommunityDmOriginCodec.sign(
                    CommunityDmOrigin(
                        "community-a",
                        "deleted-dm",
                        participants,
                        signer,
                        ByteArray(32) { 9 },
                    ),
                    secret,
                    crypto,
                )
            } finally {
                secret.fill(0)
            }
        val tombstoneParticipants =
            if (wrongPeer) {
                listOf(
                    participants.first(),
                    CommunityDmParticipant(otherPeer.copySignPublic(), otherPeer.copyBoxPublic()),
                )
            } else {
                participants
            }
        val tombstoneSecret = identity.copySignSecret()
        val signed =
            try {
                CommunityTombstoneCodec.sign(
                    CommunityTombstone(
                        "community-a",
                        "deleted-dm",
                        CommunityDmOriginCodec.contentAddress(originBytes),
                        TombstoneScope.DM,
                        1,
                        TombstoneSignerRole.DM_INITIATOR,
                        signer,
                        tombstoneParticipants,
                    ),
                    tombstoneSecret,
                    crypto,
                )
            } finally {
                tombstoneSecret.fill(0)
            }
        val sealed = envelopes.seal(GovernanceObjectKind.DM_TOMBSTONE, "community-a", key, signed)
        return ChatDeletionBackup(
            "deleted-dm",
            "DM",
            "ORIGIN_HASH",
            CommunityDmOriginCodec.contentAddress(originBytes),
            CommunityTombstoneCodec.contentAddress(signed),
            Base64.getEncoder().encodeToString(sealed),
            "COMPLETE",
            0,
            null,
            null,
            123L,
            Base64.getEncoder().encodeToString(signed),
            Base64.getEncoder().encodeToString(originBytes),
        )
    }

    fun community(deletion: ChatDeletionBackup) =
        CommunityBackup(
            id = "community-a",
            name = "Community A",
            anchorChatId = "anchor",
            config = ConnectionConfig("https://example.test", "user", "password", "root"),
            chats = listOf(ChatBackup("anchor", "General", "general")),
            communityKeyBase64 = Base64.getEncoder().encodeToString(key.export()),
            chatDeletions = listOf(deletion),
        )
}
