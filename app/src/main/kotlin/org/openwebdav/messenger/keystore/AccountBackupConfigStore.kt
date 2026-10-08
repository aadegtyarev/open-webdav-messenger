package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.transport.ConnectionConfig

internal interface AccountBackupConfigStore {
    fun save(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        communityId: String,
    )

    fun loadStored(communityId: String): StoredConnection?

    fun loadStored(): StoredConnection?

    fun listCommunityIds(): Set<String>

    fun hasAny(): Boolean

    fun clear(communityId: String)
}
