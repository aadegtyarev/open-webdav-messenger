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
            var nextAttemptAt = 0L
            var processCount = 0
            val row = deletion()
            val queue =
                ChatDeletionRetryQueue(
                    loadDue = { now, limit ->
                        assertEquals(100_000L, now)
                        requestedLimit = limit
                        if (now >= nextAttemptAt) listOf(row) else emptyList()
                    },
                    process = {
                        processCount++
                        "receipts pending"
                    },
                    saveFailure = { deletion, attempts, next, error, updated ->
                        nextAttemptAt = next
                        saved = listOf(deletion, attempts, next, error, updated)
                    },
                    nowMillis = { 100_000L },
                )
            assertEquals(0, queue.run(limit = 99))
            assertEquals(0, queue.run(limit = 99))
            assertEquals(64, requestedLimit)
            assertEquals(listOf(row, 1, 130_000L, "receipts pending", 100_000L), saved)
            assertEquals(1, processCount)
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
