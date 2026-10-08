package org.openwebdav.messenger.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.keystore.ChatRegistry

class CommunityDirectoryKeyPolicyTest {
    private val privateAnchorKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 19 })

    @Test
    fun private_synthetic_anchor_never_becomes_community_directory_key() {
        val key =
            CommunityDirectoryKeyPolicy.resolve(
                communityAnchorId = "private-chat",
                storedAnchorId = "private-chat",
                anchor = ChatRegistry.Entry("private-chat", "Private", "group", "private"),
                communityKey = null,
                anchorKey = privateAnchorKey,
            )

        assertNull(key)
    }

    @Test
    fun exact_general_anchor_can_use_separate_community_key() {
        val communityKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 23 })
        val key =
            CommunityDirectoryKeyPolicy.resolve(
                communityAnchorId = "general-id",
                storedAnchorId = "general-id",
                anchor = ChatRegistry.Entry("general-id", "General", "general", "public"),
                communityKey = communityKey,
                anchorKey = privateAnchorKey,
            )

        assertArrayEquals(communityKey.copyBytes(), key?.copyBytes())
    }

    @Test
    fun unknown_anchor_is_not_a_community_capability_until_exact_migration_commits() {
        assertNull(
            CommunityDirectoryKeyPolicy.resolve(
                "general-id",
                "general-id",
                ChatRegistry.Entry("general-id", "General", "general", "unknown"),
                privateAnchorKey,
                privateAnchorKey,
            ),
        )
    }
}
