package org.openwebdav.messenger.export

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.transport.ConnectionConfig

/**
 * Test doubles for the four exportable stores — in-memory, JVM-compatible (no Android Keystore).
 * Each holds a single value; `null` means "not stored."
 */
internal object ExportTestSupport {
    class InMemoryAccountBackupStore(
        var value: AccountBackup? = null,
        private val onReplace: (AccountBackup) -> Unit = {},
    ) : ExportableAccountBackupStore {
        override fun snapshot(): AccountBackup? = value

        override fun replace(backup: AccountBackup) {
            value = backup
            onReplace(backup)
        }

        override fun clear() {
            value = null
        }
    }

    fun native(): NativeCrypto = CryptoTestSupport.native()

    fun inMemoryConnectionConfigStore(): InMemoryConnectionConfigStore = InMemoryConnectionConfigStore()

    fun inMemoryCommunityKeyStore(): InMemoryCommunityKeyStore = InMemoryCommunityKeyStore()

    fun inMemoryChatKeyStore(): InMemoryChatKeyStore = InMemoryChatKeyStore()

    fun inMemoryIdentityStore(): InMemoryIdentityStore = InMemoryIdentityStore()

    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native())

    fun freshIdentity(): Identity = identityCrypto().generateIdentity()

    fun sampleConfig() =
        ConnectionConfig(
            baseUrl = "https://webdav.example.com",
            username = "alice",
            appPassword = "secret-app-password-123",
            chatRoot = "owdm-chats",
        )

    class InMemoryConnectionConfigStore : ExportableConnectionConfigStore {
        private var config: ConnectionConfig? = null

        override fun load(): ConnectionConfig? = config

        override fun store(config: ConnectionConfig) {
            this.config = config
        }

        override fun clear() {
            config = null
        }
    }

    class InMemoryCommunityKeyStore : ExportableCommunityKeyStore {
        private val keys = mutableMapOf<String, ChatKey>()

        override fun load(): ChatKey? = load("default")

        override fun load(communityId: String): ChatKey? = keys[communityId]

        override fun store(key: ChatKey) = store("default", key)

        override fun store(
            communityId: String,
            key: ChatKey,
        ) {
            keys[communityId] = key
        }

        override fun remove(communityId: String) {
            keys.remove(communityId)
        }

        override fun listCommunityIds(): Set<String> = keys.keys

        override fun replaceAll(keys: Map<String, ChatKey>) {
            this.keys.clear()
            this.keys.putAll(keys)
        }

        override fun clear() {
            keys.clear()
        }
    }

    class InMemoryChatKeyStore : ExportableChatKeyStore {
        private val keys = mutableMapOf<String, ChatKey>()

        override fun load(chatId: String): ChatKey? = keys[chatId]

        override fun store(
            chatId: String,
            chatKey: ChatKey,
        ) {
            keys[chatId] = chatKey
        }

        override fun listChatIds(): List<String> = keys.keys.toList()

        override fun remove(chatId: String) {
            keys.remove(chatId)
        }

        override fun replaceAll(chatKeys: Map<String, ChatKey>) {
            keys.clear()
            keys.putAll(chatKeys)
        }
    }

    class InMemoryIdentityStore : ExportableIdentityStore {
        private var identity: Identity? = null

        override fun load(): IdentityLoadResult {
            val id = identity ?: return IdentityLoadResult.None
            return IdentityLoadResult.Loaded(id)
        }

        override fun store(identity: Identity) {
            this.identity = identity
        }

        override fun clear() {
            identity = null
        }
    }
}
