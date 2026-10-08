package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedParticipantTest {
    @Test
    fun signing_fingerprint_is_short_domain_separated_and_deterministic() {
        val key = ByteArray(32) { it.toByte() }
        val first = participantFingerprint(key)
        assertEquals(10, first.length)
        assertEquals(first, participantFingerprint(key.copyOf()))
        assertNotEquals(first, participantFingerprint(ByteArray(32) { (it + 1).toByte() }))
        assertNotEquals(org.openwebdav.messenger.protocol.Hex.encode(key), first)
    }

    @Test
    fun local_self_is_added_without_changing_verified_peer_projection() {
        val peer = VerifiedParticipant("Alex", "abc123", false)
        val projection = withSelfParticipant(listOf(peer), "signing-key".toByteArray())
        assertEquals(2, projection.size)
        assertTrue(projection.any { it.isSelf && it.displayName.isEmpty() })
        assertEquals(peer, projection.single { !it.isSelf })
    }
}
