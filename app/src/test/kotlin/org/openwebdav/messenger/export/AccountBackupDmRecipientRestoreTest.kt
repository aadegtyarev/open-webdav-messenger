package org.openwebdav.messenger.export

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityDmParticipant
import java.util.Base64

class AccountBackupDmRecipientRestoreTest {
    @Test
    fun participantBackupRestoresAfterKeyDeletionButOutsiderIdentityIsRejected() =
        runBlocking {
            val fixture = AccountBackupDmGraveyardFixture()
            val recipient = ExportTestSupport.freshIdentity()
            val initiator = ExportTestSupport.freshIdentity()
            val outsider = ExportTestSupport.freshIdentity()
            val deletion = fixture.deletion(recipient, initiator = initiator)
            assertEquals(true, fixture.isAuthenticated(deletion, fixture.participant(recipient)))
            assertEquals(false, fixture.isAuthenticated(deletion, fixture.participant(outsider)))
            assertEquals(
                false,
                fixture.isAuthenticated(
                    deletion,
                    CommunityDmParticipant(recipient.copySignPublic(), outsider.copyBoxPublic()),
                ),
            )

            val native = ExportTestSupport.native()
            val backup = AccountBackup("community-a", listOf(community(fixture.key, deletion)))
            val recipientBlob = exportBlob(native, recipient, backup, fixture.key)
            val manager = restoreManager(native)
            assertEquals(RestoreResult.Restored, manager.restore(recipientBlob, "restore-password".toCharArray()))

            val outsiderBlob = exportBlob(native, outsider, backup, fixture.key)
            assertEquals(RestoreResult.CorruptPayload, manager.restore(outsiderBlob, "restore-password".toCharArray()))
        }

    private fun community(
        key: org.openwebdav.messenger.crypto.ChatKey,
        deletion: ChatDeletionBackup,
    ): CommunityBackup =
        CommunityBackup(
            id = "community-a",
            name = "Community A",
            anchorChatId = "anchor",
            config = ExportTestSupport.sampleConfig(),
            chats = listOf(ChatBackup("anchor", "General", "general")),
            communityKeyBase64 = Base64.getEncoder().encodeToString(key.export()),
            chatDeletions = listOf(deletion),
        )

    private fun exportBlob(
        native: org.openwebdav.messenger.crypto.NativeCrypto,
        identity: org.openwebdav.messenger.identity.Identity,
        backup: AccountBackup,
        anchorKey: org.openwebdav.messenger.crypto.ChatKey,
    ): String {
        val payload = ExportPayload.build(null, null, mapOf("anchor" to anchorKey), identity, backup)
        return encryptedExportTestPayload(native, ExportPayload.toJson(payload), "restore-password")
    }

    private fun restoreManager(native: org.openwebdav.messenger.crypto.NativeCrypto): RestoreManager =
        RestoreManager(
            native,
            ExportTestSupport.inMemoryConnectionConfigStore(),
            ExportTestSupport.inMemoryCommunityKeyStore(),
            ExportTestSupport.inMemoryChatKeyStore(),
            ExportTestSupport.inMemoryIdentityStore(),
            accountBackupStore = ExportTestSupport.InMemoryAccountBackupStore(),
        )
}
