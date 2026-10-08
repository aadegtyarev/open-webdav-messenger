package org.openwebdav.messenger.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openwebdav.messenger.data.LegacyHistoryOwnerResolver
import org.openwebdav.messenger.transport.ConnectionConfig

/**
 * JVM unit test for the connection-config store's serialize/deserialize round-trip
 * (`ui-chat-surface` plan: `config_round_trips_from_secure_store`). The Keystore wrap/unwrap itself is
 * device-backed and exercised under `connectedAndroidTest`; the byte-layout round-trip (the part that does
 * not need the Keystore) is JVM-testable here. All NEW.
 */
class ConnectionConfigStoreTest {
    /** config_round_trips_from_secure_store — every field (incl. the app-password) reads back intact. */
    @Test
    fun config_round_trips_from_secure_store() {
        // Obvious fake values (SC21).
        val config =
            ConnectionConfig(
                baseUrl = "https://disk.example.test",
                username = "owner-login",
                appPassword = "fake-app-password-not-real",
                chatRoot = "owdm/community-root",
            )
        val chatId = "chatidchatidchatidchatid01"
        val communityName = "Тест сообщество 🚀"

        val serialized = ConnectionConfigStore.serialize(config, chatId, communityName)
        val restored = ConnectionConfigStore.deserialize(serialized)

        assertEquals(config.baseUrl, restored!!.config.baseUrl)
        assertEquals(config.username, restored.config.username)
        assertEquals(config.appPassword, restored.config.appPassword)
        assertEquals(config.chatRoot, restored.config.chatRoot)
        assertEquals(chatId, restored.chatId)
        assertEquals(communityName, restored.communityName)
    }

    @Test
    fun credential_rotation_keeps_community_anchor_while_group_is_open() {
        val originalConfig = ConnectionConfig("https://old.example.test", "user", "old-pass", "root")
        val rotatedConfig = ConnectionConfig("https://new.example.test", "user", "new-pass", "root")
        val stored = StoredConnection(originalConfig, "community-anchor-chat", "Community anchor")
        val openedGroupId = "opened-group-chat"

        val rotated = ConnectionConfigStore.rotatedConnection(stored, rotatedConfig)

        assertEquals(rotatedConfig, rotated.config)
        assertEquals("community-anchor-chat", rotated.chatId)
        assertEquals("Community anchor", rotated.communityName)
        assertNotEquals(openedGroupId, rotated.chatId)
    }

    @Test
    fun unreadable_enumerated_connection_disables_single_owner_repair() {
        val entry = CommunityRegistry.Entry("community-a", "A", "anchor-a")
        val readable = StoredConnection(ConnectionConfig("https://disk.example.test", "u", "p", "root"), "anchor-a", "A")
        val loadedIds = mutableListOf<String>()

        val owner =
            LegacyHistoryOwnerResolver.resolve(
                registryEntries = listOf(entry),
                physicalConfigIds = listOf("community-a", "community-b"),
                loadStored = { id ->
                    loadedIds += id
                    if (id == "community-a") readable else null
                },
            )

        assertNull(owner)
        assertEquals(listOf("community-a", "community-b"), loadedIds)
        assertEquals(
            "community-a",
            LegacyHistoryOwnerResolver.resolve(listOf(entry, entry), listOf("community-a", "community-a")) { readable },
        )
    }

    /** A corrupt / wrong-version blob deserializes to null (reject-don't-guess), never a partial config. */
    @Test
    fun deserialize_rejects_corrupt_blob() {
        assertNull(ConnectionConfigStore.deserialize(byteArrayOf(9, 9, 9)))
        assertNull(ConnectionConfigStore.deserialize(ByteArray(0)))
    }
}
