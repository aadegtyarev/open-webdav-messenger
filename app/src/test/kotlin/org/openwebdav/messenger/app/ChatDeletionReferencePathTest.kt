package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.data.ChatDeletionCommitResult
import org.openwebdav.messenger.data.ChatDeletionEntity
import org.openwebdav.messenger.governance.CommunityTombstone
import org.openwebdav.messenger.governance.CommunityTombstoneChatScanner
import org.openwebdav.messenger.governance.TombstoneOriginMode
import org.openwebdav.messenger.governance.TombstoneScope
import org.openwebdav.messenger.governance.TombstoneSignerRole
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.CommunityRegistry

class ChatDeletionReferencePathTest {
    @Test
    fun local_commit_and_remote_observation_preserve_other_community_key_reference() =
        runTest {
            val communities = listOf(CommunityRegistry.Entry("A", "A", "general-a"), CommunityRegistry.Entry("B", "B", "general-b"))
            val anchors = mapOf("A" to "general-a", "B" to "general-b")
            val chats = mapOf("A" to emptyList(), "B" to listOf(ChatRegistry.Entry("X", "X", "group")))
            val guard = ChatKeyReferenceGuard({ communities }, { anchors.keys }, { anchors[it] }, { chats[it].orEmpty() })
            val removedKeys = mutableListOf<String>()
            val purger =
                ChatDeletionPurger(
                    isCurrent = { true },
                    fenceGraph = { _, _ -> },
                    invalidateRoster = { _, _ -> },
                    invalidatePrivateMembership = { _, _ -> },
                    invalidatePendingClaim = { _, _ -> },
                    invalidateRetryCursor = { _, _ -> },
                    removeChatKey = { id ->
                        check(guard.removeIfUnreferenced("A", id) { removedKeys += id } != ChatKeyReferenceGuard.Decision.Unavailable)
                    },
                    removeRegistryEntry = { _, _ -> },
                )
            val row =
                ChatDeletionEntity(
                    communityId = "A",
                    chatId = "X",
                    scope = ChatDeletionEntity.SCOPE_GROUP,
                    originMode = TombstoneOriginMode.LEGACY_CHAT_ID.name,
                    originHash = "a".repeat(32),
                    tombstoneHash = "b".repeat(32),
                    tombstoneStoredBytes = byteArrayOf(1),
                    phase = ChatDeletionEntity.PHASE_LOCAL_COMMITTED,
                    updatedAtMillis = 1,
                )
            val local =
                ChatDeletionCoordinator(
                    commit = {
                        assertTrue(purger.purge("A", "X", "general-a", "group", removeKey = true))
                        ChatDeletionCommitResult.Committed
                    },
                    load = { it },
                    transition = { _, _, _ -> true },
                    isCurrent = { true },
                    fenceGraph = {},
                    publish = { true },
                    cleanup = { true },
                )
            assertEquals(ChatDeletionStatus.COMPLETE, local.execute(row))

            val tombstone =
                CommunityTombstone(
                    "A", "X", "a".repeat(32), TombstoneScope.GROUP, 1,
                    TombstoneSignerRole.PINNED_HOST, byteArrayOf(1), emptyList(), TombstoneOriginMode.LEGACY_CHAT_ID,
                )
            val remote =
                TombstoneReconciler(
                    isCurrent = { true },
                    scan = { CommunityTombstoneChatScanner.Result.Found(tombstone, "c".repeat(32), byteArrayOf(1), byteArrayOf(2)) },
                    authorize = { true },
                    apply = { purger.purge("A", "X", "general-a", "group", removeKey = true) },
                )
            assertEquals(TombstoneReconciler.Result.Applied, remote.reconcile())
            assertTrue(removedKeys.isEmpty())
        }
}
