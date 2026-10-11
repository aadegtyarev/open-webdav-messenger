package org.openwebdav.messenger.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityCreationPolicyCodecTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun policy_requires_pinned_host_and_canonical_grandfather_snapshot() {
        val host = crypto.generateIdentity()
        val bytes =
            CommunityCreationPolicyCodec.sign(
                "community-1",
                3,
                false,
                true,
                listOf(HASH_B, HASH_A),
                host.copySignSecret(),
                crypto,
            )
        assertTrue(bytes.size in 84..32948)
        assertTrue(host.copySignPublic().size == 32)
        val decoded = CommunityCreationPolicyCodec.verify(bytes, "community-1", host.copySignPublic(), crypto)

        assertEquals(3L, decoded?.revision)
        assertEquals(listOf(HASH_A, HASH_B), decoded?.grandfatheredOriginHashes)
        assertNull(CommunityCreationPolicyCodec.verify(bytes, "other-community", host.copySignPublic(), crypto))
        assertNull(CommunityCreationPolicyCodec.verify(bytes, "community-1", crypto.generateIdentity().copySignPublic(), crypto))
        assertNull(
            CommunityCreationPolicyCodec.verify(
                bytes.copyOf().also {
                    it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                },
                "community-1",
                host.copySignPublic(),
                crypto,
            ),
        )
    }

    @Test
    fun duplicate_or_oversized_origin_snapshots_are_rejected() {
        val host = crypto.generateIdentity()
        assertTrue(
            runCatching {
                CommunityCreationPolicyCodec.sign(
                    "community-1",
                    1,
                    true,
                    true,
                    listOf(HASH_A, HASH_A),
                    host.copySignSecret(),
                    crypto,
                )
            }.isFailure,
        )
    }

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
