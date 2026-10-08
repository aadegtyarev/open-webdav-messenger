package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.keystore.ChatRegistry

class ChatAccessRegistryCommitTest {
    @Test
    fun restore_generation_change_between_read_and_commit_rejects_descriptor() =
        runTest {
            val barrier = AccountMutationBarrier()
            val rows = mutableListOf(ChatRegistry.Entry("group", "Group", "group", "unknown"))
            val commit = committer(barrier, rows)
            val generation = barrier.replacementGeneration()
            barrier.withAccountReplacement { rows.clear() }

            assertNull(commit.commit("community", "group", ChatAccess.PRIVATE, generation) { true })
            assertEquals(emptyList<ChatRegistry.Entry>(), rows)
        }

    @Test
    fun commit_merges_fresh_registry_after_a_chat_was_created() =
        runTest {
            val barrier = AccountMutationBarrier()
            val rows = mutableListOf(ChatRegistry.Entry("group", "Group", "group", "unknown"))
            val commit = committer(barrier, rows)
            val generation = barrier.replacementGeneration()
            rows += ChatRegistry.Entry("new-group", "New", "group", "public")

            assertEquals(ChatAccess.PRIVATE, commit.commit("community", "group", ChatAccess.PRIVATE, generation) { true })
            assertEquals(listOf("private", "public"), rows.map { it.access })
        }

    @Test
    fun moved_runtime_context_rejects_descriptor_commit() =
        runTest {
            val barrier = AccountMutationBarrier()
            val rows = mutableListOf(ChatRegistry.Entry("group", "Group", "group", "unknown"))
            val generation = barrier.replacementGeneration()

            assertNull(committer(barrier, rows).commit("community", "group", ChatAccess.PUBLIC, generation) { false })
            assertEquals("unknown", rows.single().access)
        }

    private fun committer(
        barrier: AccountMutationBarrier,
        rows: MutableList<ChatRegistry.Entry>,
    ) = ChatAccessRegistryCommit(barrier, { rows.toList() }, { _, updated ->
        rows.clear()
        rows.addAll(updated)
        true
    })
}
