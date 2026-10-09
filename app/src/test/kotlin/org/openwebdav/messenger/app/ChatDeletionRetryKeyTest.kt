package org.openwebdav.messenger.app

import org.junit.Assert.assertSame
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.data.ChatDeletionEntity

class ChatDeletionRetryKeyTest {
    @Test
    fun later_dm_phases_use_community_key_without_loading_chat_key() {
        val community = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 1 })
        val dm = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 2 })
        for (phase in listOf(ChatDeletionEntity.PHASE_TOMBSTONE_PUBLISHED, ChatDeletionEntity.PHASE_CLEANUP_PENDING)) {
            assertSame(
                community,
                ChatDeletionRetryKey.select(ChatDeletionEntity.SCOPE_DM, phase, community) {
                    error("later phase must not require the deleted DM key")
                },
            )
        }
        assertSame(
            dm,
            ChatDeletionRetryKey.select(ChatDeletionEntity.SCOPE_DM, ChatDeletionEntity.PHASE_LOCAL_COMMITTED, community) { dm },
        )
    }
}
