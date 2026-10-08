package org.openwebdav.messenger.export

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.transport.ConnectionConfig

/**
 * Narrow seams over the four stores that [ExportManager] and [RestoreManager] need, so the
 * export/restore logic is testable on the JVM (the concrete Android stores depend on the
 * device-backed Keystore). Each production store implements its seam trivially.
 */

interface ExportableAccountBackupStore {
    fun snapshot(): AccountBackup?

    fun replace(backup: AccountBackup)

    fun clear()

    fun clearCommunityIds(ids: Set<String>) = clear()

    fun hasMembershipState(): Boolean = snapshot() != null

    fun hasRegistryState(): Boolean = snapshot() != null
}

interface ExportableConnectionConfigStore {
    fun load(): ConnectionConfig?

    /** Physical presence distinguishes an absent config from an encrypted config that failed to load. */
    fun hasStored(): Boolean = load() != null

    fun store(config: ConnectionConfig)

    fun clear()
}

interface ExportableCommunityKeyStore {
    fun load(): ChatKey?

    fun store(key: ChatKey)

    fun clear()

    fun load(communityId: String): ChatKey? = if (communityId == "default") load() else null

    fun store(
        communityId: String,
        key: ChatKey,
    ) {
        if (communityId == "default") store(key) else error("Community-scoped key storage is unavailable")
    }

    fun remove(communityId: String) {
        if (communityId == "default") clear()
    }

    fun listCommunityIds(): Set<String> = if (load() != null) setOf("default") else emptySet()

    fun replaceAll(keys: Map<String, ChatKey>) {
        clear()
        keys.forEach { (communityId, key) -> store(communityId, key) }
    }

    fun replaceAllStrict(keys: Map<String, ChatKey>) = replaceAll(keys)
}

interface ExportableChatKeyStore {
    fun load(chatId: String): ChatKey?

    fun store(
        chatId: String,
        chatKey: ChatKey,
    )

    fun storeStrict(
        chatId: String,
        chatKey: ChatKey,
    ) = store(chatId, chatKey)

    fun listChatIds(): List<String>

    fun remove(chatId: String)

    fun removeStrict(chatId: String) = remove(chatId)

    fun replaceAll(chatKeys: Map<String, ChatKey>)

    fun replaceAllStrict(chatKeys: Map<String, ChatKey>) = replaceAll(chatKeys)
}

interface ExportableIdentityStore {
    fun load(): IdentityLoadResult

    fun store(identity: Identity)

    fun clear()
}
