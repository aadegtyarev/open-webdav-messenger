package org.openwebdav.messenger.membership

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateClaimRetryQueueTest {
    @Test
    fun pending_retry_limit_rotates_past_first_sixty_four_without_uploaded_entries() {
        val pending = (0..64).map { PrivateClaimRetryCandidate("chat-$it", pending = true) }
        val uploadedAndNew = listOf(PrivateClaimRetryCandidate("fresh", pending = false))

        val first = PrivateClaimRetryQueue.select(pending + uploadedAndNew, null, 64, 1)
        val second = PrivateClaimRetryQueue.select(pending + uploadedAndNew, first[63].chatId, 64, 1)

        assertEquals(64, first.count { it.pending })
        assertEquals(1, first.count { !it.pending })
        assertEquals(65, (first + second).filter { it.pending }.map { it.chatId }.distinct().size)
    }
}
