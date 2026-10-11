package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDeletionPurgerTest {
    @Test
    fun purge_removes_private_group_key_but_preserves_shared_anchor_key() {
        val removedKeys = mutableListOf<String>()
        var invalidated = 0
        val purger =
            ChatDeletionPurger(
                isCurrent = { true },
                fenceGraph = { _, _ -> invalidated++ },
                invalidateRoster = { _, _ -> invalidated++ },
                invalidatePrivateMembership = { _, _ -> invalidated++ },
                invalidatePendingClaim = { _, _ -> invalidated++ },
                invalidateRetryCursor = { _, _ -> invalidated++ },
                removeChatKey = removedKeys::add,
                removeRegistryEntry = { _, _ -> invalidated++ },
            )

        assertTrue(purger.purge("community", "private-group", "general", "group", removeKey = true))
        assertFalse(purger.purge("community", "general", "general", "group", removeKey = true))

        assertEquals(listOf("private-group"), removedKeys)
        assertEquals(6, invalidated)
    }
}
