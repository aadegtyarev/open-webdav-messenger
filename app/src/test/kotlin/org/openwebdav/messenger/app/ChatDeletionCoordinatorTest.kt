package org.openwebdav.messenger.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.data.ChatDeletionCommitResult
import org.openwebdav.messenger.data.ChatDeletionEntity

class ChatDeletionCoordinatorTest {
    @Test
    fun resumesDurableCleanupPhaseWithoutRepublishingTombstone() =
        runBlocking {
            var phase = ChatDeletionEntity.PHASE_LOCAL_COMMITTED
            var publishCount = 0
            var cleanupCount = 0
            val row = deletion()
            val coordinator =
                ChatDeletionCoordinator(
                    commit = { ChatDeletionCommitResult.AlreadyCommitted },
                    load = { row.copy(phase = phase) },
                    transition = { _, expected, next ->
                        if (phase == expected) {
                            phase = next
                            true
                        } else {
                            false
                        }
                    },
                    isCurrent = { true },
                    fenceGraph = {},
                    publish = {
                        publishCount++
                        true
                    },
                    cleanup = {
                        cleanupCount++
                        cleanupCount > 1
                    },
                )
            assertEquals(ChatDeletionStatus.CLEANUP_PENDING, coordinator.execute(row))
            assertEquals(ChatDeletionStatus.COMPLETE, coordinator.execute(row))
            assertEquals(1, publishCount)
            assertEquals(2, cleanupCount)
        }

    private fun deletion() =
        ChatDeletionEntity(
            communityId = "community-1",
            chatId = "chat-1",
            scope = ChatDeletionEntity.SCOPE_GROUP,
            originHash = "a".repeat(32),
            tombstoneHash = "b".repeat(32),
            tombstoneStoredBytes = byteArrayOf(1),
            phase = ChatDeletionEntity.PHASE_LOCAL_COMMITTED,
            updatedAtMillis = 1,
        )
}
