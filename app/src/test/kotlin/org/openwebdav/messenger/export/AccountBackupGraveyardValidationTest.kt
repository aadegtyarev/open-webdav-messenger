package org.openwebdav.messenger.export

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBackupGraveyardValidationTest {
    @Test
    fun signed_then_sealed_tombstone_survives_export_codec_and_production_restore() =
        runBlocking {
            val fixture = AccountBackupGraveyardFixture()
            val backup = AccountBackup("community-a", listOf(fixture.community(fixture.deletion())))
            val native = ExportTestSupport.native()
            val identity = ExportTestSupport.freshIdentity()
            val payload = ExportPayload.build(null, null, mapOf("anchor" to fixture.key), identity, backup)
            val blob = encryptedExportTestPayload(native, ExportPayload.toJson(payload), "restore-password")
            val accountStore = ExportTestSupport.InMemoryAccountBackupStore()
            val manager =
                RestoreManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    ExportTestSupport.inMemoryChatKeyStore(),
                    ExportTestSupport.inMemoryIdentityStore(),
                    accountBackupStore = accountStore,
                )

            assertEquals(RestoreResult.Restored, manager.restore(blob, "restore-password".toCharArray()))
            assertTrue(fixture.isAuthenticated(accountStore.snapshot()!!.communities.single().chatDeletions.single()))
        }

    @Test
    fun dm_signed_evidence_validates_after_dm_key_is_absent_and_rejects_wrong_peer_or_missing_evidence() =
        runBlocking {
            val fixture = AccountBackupGraveyardFixture()
            val identity = ExportTestSupport.freshIdentity()
            val deletion = fixture.dmDeletion(identity)
            assertTrue(fixture.isAuthenticated(deletion, communityKey = null, expectedSigner = identity.copySignPublic()))
            assertFalse(
                fixture.isAuthenticated(
                    fixture.dmDeletion(identity, wrongPeer = true),
                    communityKey = null,
                    expectedSigner = identity.copySignPublic(),
                ),
            )
            assertFalse(
                fixture.isAuthenticated(
                    deletion.copy(tombstonePlaintextBytesBase64 = null),
                    communityKey = null,
                    expectedSigner = identity.copySignPublic(),
                ),
            )
            val tampered = java.util.Base64.getDecoder().decode(deletion.tombstonePlaintextBytesBase64)
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
            assertFalse(
                fixture.isAuthenticated(
                    deletion.copy(tombstonePlaintextBytesBase64 = java.util.Base64.getEncoder().encodeToString(tampered)),
                    communityKey = null,
                    expectedSigner = identity.copySignPublic(),
                ),
            )
            assertFalse(fixture.isAuthenticated(deletion, communityId = "wrong-community", communityKey = null))
            assertFalse(
                fixture.isAuthenticated(
                    deletion.copy(chatId = "wrong-chat"),
                    communityKey = null,
                    expectedSigner = identity.copySignPublic(),
                ),
            )
            assertFalse(
                fixture.isAuthenticated(
                    deletion.copy(originHash = "b".repeat(32)),
                    communityKey = null,
                    expectedSigner = identity.copySignPublic(),
                ),
            )
            assertFalse(
                fixture.isAuthenticated(
                    deletion.copy(tombstoneHash = "b".repeat(32)),
                    communityKey = null,
                    expectedSigner = identity.copySignPublic(),
                ),
            )
            val backup = AccountBackup("community-a", listOf(fixture.community(deletion)))
            val payload = ExportPayload.build(null, null, mapOf("anchor" to fixture.key), identity, backup)
            val blob = encryptedExportTestPayload(ExportTestSupport.native(), ExportPayload.toJson(payload), "restore-password")
            val manager =
                RestoreManager(
                    ExportTestSupport.native(),
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    ExportTestSupport.inMemoryChatKeyStore(),
                    ExportTestSupport.inMemoryIdentityStore(),
                    accountBackupStore = ExportTestSupport.InMemoryAccountBackupStore(),
                )
            assertEquals(RestoreResult.Restored, manager.restore(blob, "restore-password".toCharArray()))
        }

    @Test
    fun dm_recipient_backup_round_trips_after_key_deletion_and_outsider_identity_fails_closed() =
        runBlocking {
            val fixture = AccountBackupGraveyardFixture()
            val recipient = ExportTestSupport.freshIdentity()
            val initiator = ExportTestSupport.freshIdentity()
            val outsider = ExportTestSupport.freshIdentity()
            val deletion = fixture.dmDeletion(recipient, initiator = initiator)
            assertTrue(fixture.isAuthenticated(deletion, communityKey = null, expectedSigner = recipient.copySignPublic()))
            assertFalse(fixture.isAuthenticated(deletion, communityKey = null, expectedSigner = outsider.copySignPublic()))

            val native = ExportTestSupport.native()
            val payload =
                ExportPayload.build(
                    null,
                    null,
                    mapOf("anchor" to fixture.key),
                    recipient,
                    AccountBackup("community-a", listOf(fixture.community(deletion))),
                )
            val blob = encryptedExportTestPayload(native, ExportPayload.toJson(payload), "restore-password")
            val manager =
                RestoreManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    ExportTestSupport.inMemoryChatKeyStore(),
                    ExportTestSupport.inMemoryIdentityStore(),
                    accountBackupStore = ExportTestSupport.InMemoryAccountBackupStore(),
                )
            assertEquals(RestoreResult.Restored, manager.restore(blob, "restore-password".toCharArray()))

            val outsiderPayload =
                ExportPayload.build(
                    null,
                    null,
                    mapOf("anchor" to fixture.key),
                    outsider,
                    AccountBackup("community-a", listOf(fixture.community(deletion))),
                )
            val outsiderBlob = encryptedExportTestPayload(native, ExportPayload.toJson(outsiderPayload), "restore-password")
            assertEquals(RestoreResult.CorruptPayload, manager.restore(outsiderBlob, "restore-password".toCharArray()))
        }

    @Test
    fun sealed_tombstone_tamper_and_plaintext_hash_mismatch_fail_closed() {
        val fixture = AccountBackupGraveyardFixture()
        val deletion = fixture.deletion()

        assertFalse(fixture.isAuthenticated(fixture.tamperCiphertext(deletion)))
        assertFalse(fixture.isAuthenticated(deletion.copy(tombstoneHash = "b".repeat(32))))
        assertFalse(fixture.isAuthenticated(deletion, communityId = "other-community"))
    }
}
