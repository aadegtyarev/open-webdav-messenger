package org.openwebdav.messenger.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.protocol.Base32
import org.openwebdav.messenger.protocol.Hex

class ChatDescriptorIdMatcherTest {
    @Test
    fun matches_hex_groups_and_base32_general_anchors() {
        val id = ByteArray(16) { it.toByte() }

        assertTrue(ChatDescriptorIdMatcher.matches(id, Hex.encode(id)))
        assertTrue(ChatDescriptorIdMatcher.matches(id, Base32.encodeBase32Lower(id)))
    }
}
