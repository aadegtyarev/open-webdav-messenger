package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openwebdav.messenger.directory.DirectoryEntry

class RosterCacheCodecTest {
    @Test
    fun codec_roundtrips_and_rejects_invalid_data() {
        val entry = DirectoryEntry("Peer", ByteArray(32) { 3 }, ByteArray(32) { 4 })
        val roster = CachedVerifiedRoster("community", "chat", ByteArray(32), listOf(entry))
        val bytes = RosterCacheCodec.encode(listOf(roster))
        assertEquals("Peer", RosterCacheCodec.decode(bytes)!!.single().entries.single().displayName)
        assertNull(RosterCacheCodec.decode(bytes + byteArrayOf(0)))
        assertNull(RosterCacheCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(RosterCacheCodec.decode(ByteArray(RosterCacheCodec.MAX_FILE_BYTES + 1)))
        assertThrows(IllegalArgumentException::class.java) {
            RosterCacheCodec.encode(listOf(roster.copy(entries = listOf(entry, entry))))
        }
        val longName = DirectoryEntry("x".repeat(257), ByteArray(32), ByteArray(32))
        assertThrows(IllegalArgumentException::class.java) {
            RosterCacheCodec.encode(listOf(roster.copy(entries = listOf(longName))))
        }
    }
}
