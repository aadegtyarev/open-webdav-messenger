package org.openwebdav.messenger.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityTrustRecord

class CommunityTrustStoreCodecTest {
    @Test
    fun round_trip_preserves_pins_and_policy_high_water_mark() {
        val record = CommunityTrustRecord("community-1", HASH, ByteArray(32) { 1 }, ByteArray(32) { 2 }, true, 9, HASH)
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
        val valid = CommunityTrustRecord("community-1", HASH, ByteArray(32), ByteArray(32), true, 1, HASH)
        val bytes = CommunityTrustStoreCodec.encode(listOf(valid))
        assertNull(CommunityTrustStoreCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(CommunityTrustStoreCodec.decode(bytes + byteArrayOf(0)))
        assertTrue(runCatching { CommunityTrustStoreCodec.encode(listOf(valid, valid)) }.isFailure)
    }

    private companion object {
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
