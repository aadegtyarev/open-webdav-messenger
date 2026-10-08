package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedParticipantTest {
    @Test
    fun signing_digest_is_full_domain_separated_and_deterministic() {
        val key = ByteArray(32) { it.toByte() }
        val first = participantDigest(key)
        assertEquals(64, first.length)
        assertEquals(first, participantDigest(key.copyOf()))
        assertNotEquals(first, participantDigest(ByteArray(32) { (it + 1).toByte() }))
        assertNotEquals(org.openwebdav.messenger.protocol.Hex.encode(key), first)
    }

    @Test
    fun local_self_is_added_without_changing_verified_peer_projection() {
        val peer = VerifiedParticipant("Alex", "a".repeat(64), false)
        val projection = withSelfParticipant(listOf(peer), "signing-key".toByteArray())
        assertEquals(2, projection.size)
        assertTrue(projection.any { it.isSelf && it.displayName.isEmpty() })
        assertEquals(peer, projection.single { !it.isSelf })
    }
}
