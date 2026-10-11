package org.openwebdav.messenger.export

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.keystore.AccountBackupSnapshotWriter
import org.openwebdav.messenger.keystore.ActiveCommunityStore
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.CommunityRegistry
import org.openwebdav.messenger.keystore.CommunityTrustBackupStore
import org.openwebdav.messenger.keystore.FakeAccountBackupConfigStore
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductionAdapterTrustRestoreTest {
    @Test
    fun legacy_backup_without_trust_fields_preserves_existing_pin() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val identityCrypto = IdentityTestSupport.identityCrypto()
        val config = FakeAccountBackupConfigStore()
        config.save(ExportTestSupport.sampleConfig(), "anchor-a", "A", "community-a")
        val communities = CommunityRegistry(context)
        communities.replace(listOf(CommunityRegistry.Entry("community-a", "A", "anchor-a")))
        val pinned =
            CommunityTrustRecord("community-a", "a".repeat(32), byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), true, 9, "b".repeat(32))
        val trust = RecordingTrustStore(pinned)
        val writer =
            AccountBackupSnapshotWriter(
                context,
                config,
                communities,
                ChatRegistry(context),
                ActiveCommunityStore(context),
                identityCrypto,
                trust,
            )
        try {
            val oldBackupCommunity =
                CommunityBackup(
                    "community-a",
                    "A",
                    "anchor-a",
                    ExportTestSupport.sampleConfig(),
                    listOf(ChatBackup("anchor-a", "General", "general", "unknown")),
                )
            writer.write(AccountBackup("community-a", listOf(oldBackupCommunity)))
            assertEquals(pinned, trust.record)
        } finally {
            writer.clear(setOf("community-a"))
        }
    }

    private class RecordingTrustStore(var record: CommunityTrustRecord?) : CommunityTrustBackupStore {
        override fun restoreRecord(
            communityId: String,
            genesisBytes: ByteArray,
            fingerprintVerified: Boolean,
            policyRevision: Long,
            policyHash: String?,
            crypto: org.openwebdav.messenger.identity.IdentityCrypto,
        ) {
            record =
                CommunityTrustRecord(
                    communityId,
                    "z".repeat(32),
                    genesisBytes,
                    byteArrayOf(),
                    byteArrayOf(),
                    fingerprintVerified,
                    policyRevision,
                    policyHash,
                )
        }

        override fun remove(communityId: String) {
            if (record?.communityId == communityId) record = null
        }
    }
}
