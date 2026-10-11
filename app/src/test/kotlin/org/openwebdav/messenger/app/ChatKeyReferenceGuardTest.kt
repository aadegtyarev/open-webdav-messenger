package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.CommunityRegistry

class ChatKeyReferenceGuardTest {
    @Test
    fun cross_community_general_and_group_references_preserve_shared_chat_key() {
        val communities = mutableListOf(CommunityRegistry.Entry("A", "A", "general-a"), CommunityRegistry.Entry("B", "B", "general-b"))
        val anchors = mutableMapOf("A" to "general-a", "B" to "general-b")
        val chats = mutableMapOf("A" to listOf(ChatRegistry.Entry("X", "X", "group")), "B" to listOf(ChatRegistry.Entry("X", "X", "group")))
        val guard = ChatKeyReferenceGuard({ communities }, { anchors.keys }, { anchors[it] }, { chats[it].orEmpty() })
        val removedKeys = mutableListOf<String>()

        assertEquals(ChatKeyReferenceGuard.Decision.Referenced, guard.removeIfUnreferenced("A", "X") { removedKeys += "X" })
        chats["B"] = emptyList()
        assertEquals(ChatKeyReferenceGuard.Decision.Unreferenced, guard.removeIfUnreferenced("A", "X") { removedKeys += "X" })
        assertEquals(listOf("X"), removedKeys)
        removedKeys.clear()

        communities[1] = CommunityRegistry.Entry("B", "B", "X")
        anchors["B"] = "X"
        assertEquals(ChatKeyReferenceGuard.Decision.Referenced, guard.removeIfUnreferenced("A", "X") { removedKeys += "X" })
        assertEquals(emptyList<String>(), removedKeys)
        communities.removeAt(1)
        anchors.remove("B")
        assertEquals(ChatKeyReferenceGuard.Decision.Unreferenced, guard.removeIfUnreferenced("A", "X") { removedKeys += "X" })
        assertEquals(listOf("X"), removedKeys)
    }

    @Test
    fun unreadable_community_state_fails_closed() {
        val guard = ChatKeyReferenceGuard({ error("unreadable") }, { emptySet() }, { null }, { emptyList() })
        assertEquals(ChatKeyReferenceGuard.Decision.Unavailable, guard.decision("A", "X"))
    }
}
