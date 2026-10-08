package org.openwebdav.messenger.membership

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMembershipCacheCodecTest {
    @Test
    fun cache_round_trip_preserves_context_and_provenance() {
        val record =
            PrivateMembershipCacheRecord(
                "community-a",
                "chat-a",
                ByteArray(32) { it.toByte() },
                listOf(PrivateMembershipCacheMember("Alice", ByteArray(32) { 1 }, ByteArray(32) { 2 }, true)),
            )
        val decoded = PrivateMembershipCacheCodec.decode(PrivateMembershipCacheCodec.encode(listOf(record)))

        assertEquals("community-a", decoded?.single()?.communityId)
        assertEquals("Alice", decoded?.single()?.members?.single()?.displayName)
        assertTrue(decoded?.single()?.members?.single()?.directoryVerified == true)
    }

    @Test
    fun malformed_duplicate_or_oversized_cache_is_rejected() {
        val record = PrivateMembershipCacheRecord("community-a", "chat-a", ByteArray(32), emptyList())
        val encoded = PrivateMembershipCacheCodec.encode(listOf(record))

        assertNull(PrivateMembershipCacheCodec.decode(encoded + byteArrayOf(0)))
        assertNull(PrivateMembershipCacheCodec.decode(ByteArray(PrivateMembershipCacheCodec.MAX_BYTES + 1)))
        val payload = encoded.copyOfRange(7, encoded.size)
        val duplicate =
            encoded.copyOf(encoded.size + payload.size).also {
                it[6] = 2
                payload.copyInto(it, encoded.size)
            }
        assertNull(PrivateMembershipCacheCodec.decode(duplicate))
    }
}
