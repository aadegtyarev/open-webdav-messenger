package org.openwebdav.messenger.export

import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.governance.CommunityGovernanceEnvelopeCodec
import org.openwebdav.messenger.governance.CommunityTombstone
import org.openwebdav.messenger.governance.CommunityTombstoneCodec
import org.openwebdav.messenger.governance.GovernanceObjectKind
import org.openwebdav.messenger.governance.TombstoneScope
import org.openwebdav.messenger.governance.TombstoneSignerRole
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
    ): Boolean =
        AccountBackupGraveyardValidation.hasAuthenticatedTombstones(
            communityId,
            key,
            listOf(deletion),
            envelopes,
            crypto,
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
