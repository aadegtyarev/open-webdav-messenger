package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.openwebdav.messenger.account.AccountMutationBarrier

class ChatDeletionKeyRemovalTest {
    @Test
    fun account_replacement_after_publication_fences_stale_key_removal() =
        runTest {
            val barrier = AccountMutationBarrier()
            val keys = mutableMapOf("chat-x" to "old-key")
            var config = "original-config"
            val deletionGeneration = barrier.replacementGeneration()
            val publicationSucceeded = true
            assertEquals(true, publicationSucceeded)

            barrier.withAccountReplacement {
                config = "replacement-config"
                keys["chat-x"] = "replacement-key"
                commitGeneration()
            }
            val removal = ChatDeletionKeyRemoval(barrier::replacementGeneration)
            val removed =
                barrier.withStableAccount {
                    removal.removeLocked(deletionGeneration, { config == "original-config" }) {
                        keys.remove("chat-x") != null
                    }
                }

            assertFalse(removed)
            assertEquals("replacement-key", keys["chat-x"])
        }
}
