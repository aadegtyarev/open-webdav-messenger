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

interface ExportableConnectionConfigStore {
    fun load(): ConnectionConfig?

    fun store(config: ConnectionConfig)
}

interface ExportableCommunityKeyStore {
    fun load(): ChatKey?

    fun store(key: ChatKey)
}

interface ExportableChatKeyStore {
    fun load(chatId: String): ChatKey?

    fun store(
        chatId: String,
        chatKey: ChatKey,
    )

    fun listChatIds(): List<String>
}

interface ExportableIdentityStore {
    fun load(): IdentityLoadResult

    fun store(identity: Identity)
}
