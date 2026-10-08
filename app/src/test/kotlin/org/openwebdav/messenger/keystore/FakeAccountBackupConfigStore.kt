package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.export.ExportableConnectionConfigStore
import org.openwebdav.messenger.transport.ConnectionConfig

internal class FakeAccountBackupConfigStore : AccountBackupConfigStore, ExportableConnectionConfigStore {
    private val entries = mutableMapOf<String, StoredConnection>()

    override fun save(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        communityId: String,
    ) {
        entries[communityId] = StoredConnection(config, chatId, communityName)
    }

    override fun loadStored(communityId: String): StoredConnection? = entries[communityId]

    override fun loadStored(): StoredConnection? = loadStored(ConnectionConfigStore.DEFAULT_COMMUNITY_ID)

    override fun listCommunityIds(): Set<String> = entries.keys

    override fun hasAny(): Boolean = entries.isNotEmpty()

    override fun clear(communityId: String) {
        entries.remove(communityId)
    }

    override fun load(): ConnectionConfig? = loadStored()?.config

    override fun hasStored(): Boolean = loadStored() != null

    override fun store(config: ConnectionConfig) = save(config, "", "", ConnectionConfigStore.DEFAULT_COMMUNITY_ID)

    override fun clear() = clear(ConnectionConfigStore.DEFAULT_COMMUNITY_ID)
}
