package org.openwebdav.messenger.keystore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatRegistryTest {
    @Test
    fun locally_created_private_marker_and_observed_peer_survive_registry_reload() {
        val registry = ChatRegistry(RuntimeEnvironment.getApplication())
        registry.clear("self-room-test")
        registry.add(
            "self-room-test",
            ChatRegistry.Entry("private-room", "Room", "group", "private", locallyCreatedPrivate = true),
        )

        val created = ChatRegistry(RuntimeEnvironment.getApplication()).all("self-room-test").single()
        assertTrue(created.locallyCreatedPrivate)
        assertFalse(created.nonSelfMemberObserved)

        registry.markNonSelfMemberObserved("self-room-test", "private-room")
        val observed = ChatRegistry(RuntimeEnvironment.getApplication()).all("self-room-test").single()
        assertTrue(observed.locallyCreatedPrivate)
        assertTrue(observed.nonSelfMemberObserved)
        registry.clear("self-room-test")
    }

    @Test
    fun imported_or_legacy_registry_rows_are_not_self_only_eligible() {
        val registry = ChatRegistry(RuntimeEnvironment.getApplication())
        registry.clear("self-room-import-test")
        registry.add("self-room-import-test", ChatRegistry.Entry("joined", "Joined", "group", "private"))

        val imported = ChatRegistry(RuntimeEnvironment.getApplication()).all("self-room-import-test").single()
        assertFalse(imported.locallyCreatedPrivate)
        assertFalse(imported.nonSelfMemberObserved)
        registry.clear("self-room-import-test")
    }
}
