package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.data.ChatDeletionEntity

class ChatDeletionRetryQueueTest {
    @Test
    fun failurePersistsAttemptBackoffAndError() {
        runTest {
            var saved: List<Any?>? = null
            var requestedLimit = 0
            val row = deletion()
            val queue =
                ChatDeletionRetryQueue(
                    loadDue = { now, limit ->
                        assertEquals(100_000L, now)
                        requestedLimit = limit
                        listOf(row)
                    },
                    process = { "receipts pending" },
                    saveFailure = { deletion, attempts, next, error, updated ->
                        saved = listOf(deletion, attempts, next, error, updated)
                    },
                    nowMillis = { 100_000L },
                )
            assertEquals(0, queue.run(limit = 99))
            assertEquals(64, requestedLimit)
            assertEquals(listOf(row, 1, 130_000L, "receipts pending", 100_000L), saved)
        }
    }

    private fun deletion() =
        ChatDeletionEntity(
            communityId = "c",
            chatId = "abcdefghijklmnopqrstuvwxyz234567",
            scope = ChatDeletionEntity.SCOPE_GROUP,
            originHash = "a".repeat(32),
            tombstoneHash = "b".repeat(32),
            tombstoneStoredBytes = byteArrayOf(1),
            phase = ChatDeletionEntity.PHASE_CLEANUP_PENDING,
            updatedAtMillis = 100,
        )
}
