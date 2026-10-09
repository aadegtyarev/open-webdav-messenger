package org.openwebdav.messenger.governance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport

class CommunityGovernanceEnvelopeCodecTest {
    private val codec = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
    private val communityKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 })
    private val dmKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 6 })

    @Test
    fun sealed_object_binds_domain_kind_community_and_exact_file_hash() {
        val plaintext = "canonical-object".toByteArray()
        val stored = codec.seal(GovernanceObjectKind.GROUP_ORIGIN, "community-1", communityKey, plaintext)

        assertArrayEquals(plaintext, codec.open(GovernanceObjectKind.GROUP_ORIGIN, "community-1", communityKey, stored))
        assertNull(codec.open(GovernanceObjectKind.DM_ORIGIN, "community-1", communityKey, stored))
        assertNull(codec.open(GovernanceObjectKind.GROUP_ORIGIN, "community-2", communityKey, stored))
        assertNull(codec.open(GovernanceObjectKind.GROUP_ORIGIN, "community-1", dmKey, stored))
        assertTrue(codec.contentHash(stored).matches(Regex("[a-z2-7]{32}")))
        val resealed = codec.seal(GovernanceObjectKind.GROUP_ORIGIN, "community-1", communityKey, plaintext)
        assertNotEquals(codec.contentHash(stored), codec.contentHash(resealed))
    }

    @Test
    fun tampering_bounds_and_governance_paths_fail_closed() {
        val stored = codec.seal(GovernanceObjectKind.DM_TOMBSTONE, "community-1", dmKey, byteArrayOf(1, 2, 3))
        stored[stored.lastIndex] = (stored.last().toInt() xor 1).toByte()
        assertNull(codec.open(GovernanceObjectKind.DM_TOMBSTONE, "community-1", dmKey, stored))
        assertTrue(runCatching { codec.seal(GovernanceObjectKind.POLICY, "../escape", communityKey, byteArrayOf(1)) }.isFailure)
        val hash = "a".repeat(32)
        assertEquals("governance/origins/group/$hash", CommunityGovernancePaths.objectPath(GovernanceObjectKind.GROUP_ORIGIN, hash))
        assertEquals(
            listOf("governance", "governance/origins", "governance/origins/group"),
            CommunityGovernancePaths.requiredCollections(GovernanceObjectKind.GROUP_ORIGIN),
        )
        assertTrue(runCatching { CommunityGovernancePaths.objectPath(GovernanceObjectKind.GENESIS, "../escape") }.isFailure)
    }
}
