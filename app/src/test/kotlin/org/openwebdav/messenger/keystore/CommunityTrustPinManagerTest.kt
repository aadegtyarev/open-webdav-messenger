package org.openwebdav.messenger.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityTrustPinManagerTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun observed_genesis_requires_exact_fingerprint_before_host_authority() {
        val host = crypto.generateIdentity()
        val persistence = MemoryPersistence()
        val manager = CommunityTrustPinManager(persistence)
        val genesis = genesis(host)

        assertEquals(TrustPinResult.PINNED, manager.pin(COMMUNITY, genesis, crypto, TrustPinSource.OBSERVED))
        assertFalse(persistence.load().single().fingerprintVerified)
        assertFalse(persistence.load().single().hostSigningPublicKey.contentEquals(ByteArray(32)))
        val verifier = CommunityTrustFingerprintVerifier(persistence)
        assertFalse(verifier.verify(COMMUNITY, "wrong"))
        assertFalse(persistence.load().single().fingerprintVerified)
        assertTrue(verifier.verify(COMMUNITY, CommunityTrustFingerprint.of(host.copySignPublic())))
        assertTrue(persistence.load().single().fingerprintVerified)
    }

    @Test
    fun fresh_governance_invite_pins_verified_key_and_rejects_replacement() {
        val host = crypto.generateIdentity()
        val attacker = crypto.generateIdentity()
        val persistence = MemoryPersistence()
        val manager = CommunityTrustPinManager(persistence)

        assertEquals(TrustPinResult.PINNED, manager.pin(COMMUNITY, genesis(host), crypto, TrustPinSource.GOVERNANCE_INVITE))
        assertTrue(persistence.load().single().fingerprintVerified)
        assertEquals(TrustPinResult.CONFLICT, manager.pin(COMMUNITY, genesis(attacker), crypto, TrustPinSource.GOVERNANCE_INVITE))
        assertTrue(persistence.load().single().hostSigningPublicKey.contentEquals(host.copySignPublic()))
    }

    @Test
    fun forged_or_wrong_context_genesis_is_rejected() {
        val host = crypto.generateIdentity()
        val manager = CommunityTrustPinManager(MemoryPersistence())
        assertEquals(TrustPinResult.INVALID_GENESIS, manager.pin("another-community", genesis(host), crypto, TrustPinSource.OBSERVED))
        assertEquals(TrustPinResult.INVALID_GENESIS, manager.pin(COMMUNITY, ByteArray(8), crypto, TrustPinSource.OBSERVED))
    }

    private fun genesis(host: org.openwebdav.messenger.identity.Identity): ByteArray =
        CommunityGenesisCodec.sign(
            COMMUNITY,
            host.copySignPublic(),
            host.copyBoxPublic(),
            ByteArray(32) { 9 },
            host.copySignSecret(),
            crypto,
        )

    private class MemoryPersistence(initial: List<CommunityTrustRecord> = emptyList()) : CommunityTrustPersistence {
        private var records = initial

        override fun load() = records

        override fun save(records: List<CommunityTrustRecord>) {
            this.records = records
        }
    }

    private companion object {
        const val COMMUNITY = "community-1"
    }
}
