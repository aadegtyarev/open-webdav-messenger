package org.openwebdav.messenger.membership

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateClaimRetryQueueTest {
    @Test
    fun pending_retry_limit_rotates_past_first_sixty_four_without_uploaded_entries() {
        val pending = (0..64).map { PrivateClaimRetryCandidate("chat-$it", pending = true) }
        val uploadedAndNew = listOf(PrivateClaimRetryCandidate("fresh", pending = false))

        val first = PrivateClaimRetryQueue.select(pending + uploadedAndNew, null, null, 64, 1)
        val second = PrivateClaimRetryQueue.select(pending + uploadedAndNew, first[63].chatId, "fresh", 64, 1)

        assertEquals(64, first.count { it.pending })
        assertEquals(1, first.count { !it.pending })
        assertEquals(65, (first + second).filter { it.pending }.map { it.chatId }.distinct().size)
    }

    @Test
    fun repeatedly_failing_fresh_z_does_not_reset_pending_progress() {
        val pending = (0..64).map { PrivateClaimRetryCandidate("pending-${it.toString().padStart(2, '0')}", pending = true) }
        val freshZ = PrivateClaimRetryCandidate("z", pending = false)
        var pendingCursor: String? = null
        var freshCursor: String? = null
        val attemptedPending = mutableSetOf<String>()

        repeat(3) {
            val selected = PrivateClaimRetryQueue.select(pending + freshZ, pendingCursor, freshCursor, 64, 1)
            val batch = selected.filter { it.pending }
            attemptedPending += batch.map { it.chatId }
            pendingCursor = batch.last().chatId
            freshCursor = selected.last { !it.pending }.chatId
        }

        assertEquals("pending-64", pending.last().chatId)
        assertEquals(65, attemptedPending.size)
        assertEquals("z", freshCursor)
    }
}
