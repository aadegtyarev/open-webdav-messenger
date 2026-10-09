package org.openwebdav.messenger.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityGenesisCodecTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun signed_genesis_is_bound_to_exact_community_and_content_hash() {
        val identity = crypto.generateIdentity()
        val bytes =
            CommunityGenesisCodec.sign(
                "community-1",
                identity.copySignPublic(),
                identity.copyBoxPublic(),
                ByteArray(32) { it.toByte() },
                identity.copySignSecret(),
                crypto,
            )

        val decoded = CommunityGenesisCodec.verify(bytes, "community-1", crypto)
        assertEquals("community-1", decoded?.communityId)
        assertTrue(decoded!!.hostSigningPublicKey.contentEquals(identity.copySignPublic()))
        assertNull(CommunityGenesisCodec.verify(bytes, "community-2", crypto))
        assertEquals(CommunityGenesisCodec.contentAddress(bytes), CommunityGenesisCodec.contentAddress(bytes))
        assertTrue(CommunityGenesisCodec.contentAddress(bytes).matches(Regex("[a-z2-7]{32}")))
    }

    @Test
    fun malformed_or_tampered_genesis_is_rejected() {
        val identity = crypto.generateIdentity()
        val bytes =
            CommunityGenesisCodec.sign(
                "community-1",
                identity.copySignPublic(),
                identity.copyBoxPublic(),
                ByteArray(32) { 7 },
                identity.copySignSecret(),
                crypto,
            )
        val tampered = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val wrongVersion = bytes.copyOf().also { it[4] = 0x7f }
        assertFalse(tampered.contentEquals(bytes))
        assertNull(CommunityGenesisCodec.verify(tampered, "community-1", crypto))
        assertNull(CommunityGenesisCodec.verify(wrongVersion, "community-1", crypto))
        assertNull(CommunityGenesisCodec.verify(bytes.copyOf(bytes.size - 1), "community-1", crypto))
    }
}
