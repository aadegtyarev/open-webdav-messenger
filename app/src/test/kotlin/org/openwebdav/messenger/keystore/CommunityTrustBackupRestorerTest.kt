package org.openwebdav.messenger.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityTrustBackupRestorerTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun restore_preserves_unverified_state_and_does_not_roll_back_policy_revision() {
        val pin = pin(revision = 9, verified = true)
        val persistence = MemoryTrustPersistence(listOf(pin))
        val restorer = CommunityTrustBackupRestorer(persistence)

        restorer.restore("community-1", pin.genesisRecord, false, 3, HASH, crypto)
        assertTrue(persistence.load().single().fingerprintVerified)
        assertEquals(9, persistence.load().single().highestPolicyRevision)
        assertEquals(HASH, persistence.load().single().highestPolicyHash)

        val unverified = pin(revision = 0, verified = false)
        val emptyPersistence = MemoryTrustPersistence()
        CommunityTrustBackupRestorer(emptyPersistence).restore("community-1", unverified.genesisRecord, false, 0, null, crypto)
        assertFalse(emptyPersistence.load().single().fingerprintVerified)
    }

    @Test
    fun restore_rejects_host_key_rotation_without_a_trust_chain() {
        val existing = pin(revision = 0, verified = true)
        val replacement = pin(revision = 0, verified = true)
        val restorer = CommunityTrustBackupRestorer(MemoryTrustPersistence(listOf(existing)))

        assertTrue(runCatching { restorer.restore("community-1", replacement.genesisRecord, true, 0, null, crypto) }.isFailure)
    }

    private fun pin(
        revision: Long,
        verified: Boolean,
    ): CommunityTrustRecord {
        val identity = crypto.generateIdentity()
        val genesis =
            CommunityGenesisCodec.sign(
                "community-1",
                identity.copySignPublic(),
                identity.copyBoxPublic(),
                ByteArray(32) { revision.toByte() },
                identity.copySignSecret(),
                crypto,
            )
        return CommunityTrustRecord(
            "community-1",
            CommunityGenesisCodec.contentAddress(genesis),
            genesis,
            identity.copySignPublic(),
            identity.copyBoxPublic(),
            verified,
            revision,
            if (revision == 0L) null else HASH,
        )
    }

    private class MemoryTrustPersistence(initial: List<CommunityTrustRecord> = emptyList()) : CommunityTrustPersistence {
        private var records = initial

        override fun load(): List<CommunityTrustRecord> = records

        override fun save(records: List<CommunityTrustRecord>) {
            this.records = records
        }
    }

    private companion object {
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
