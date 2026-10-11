package org.openwebdav.messenger.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityTrustStoreCodecTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun round_trip_preserves_pins_and_policy_high_water_mark() {
        val record = record(revision = 9)
        val decoded = CommunityTrustStoreCodec.decode(CommunityTrustStoreCodec.encode(listOf(record)))

        assertEquals(1, decoded?.size)
        assertEquals("community-1", decoded?.single()?.communityId)
        assertTrue(decoded!!.single().hostSigningPublicKey.contentEquals(record.hostSigningPublicKey))
        assertTrue(decoded.single().fingerprintVerified)
        assertEquals(9, decoded.single().highestPolicyRevision)
        assertEquals(HASH, decoded.single().highestPolicyHash)
    }

    @Test
    fun malformed_or_rollback_inconsistent_store_is_rejected() {
        val valid = record(revision = 1)
        val bytes = CommunityTrustStoreCodec.encode(listOf(valid))
        assertNull(CommunityTrustStoreCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(CommunityTrustStoreCodec.decode(bytes + byteArrayOf(0)))
        assertTrue(runCatching { CommunityTrustStoreCodec.encode(listOf(valid, valid)) }.isFailure)
    }

    private fun record(revision: Long): CommunityTrustRecord {
        val identity = crypto.generateIdentity()
        val genesis =
            CommunityGenesisCodec.sign(
                "community-1",
                identity.copySignPublic(),
                identity.copyBoxPublic(),
                ByteArray(32) { 9 },
                identity.copySignSecret(),
                crypto,
            )
        return CommunityTrustRecord(
            "community-1",
            CommunityGenesisCodec.contentAddress(genesis),
            genesis,
            identity.copySignPublic(),
            identity.copyBoxPublic(),
            true,
            revision,
            HASH,
        )
    }

    private companion object {
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
