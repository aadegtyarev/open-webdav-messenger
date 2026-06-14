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
    }

    class InMemoryCommunityKeyStore : ExportableCommunityKeyStore {
        private var key: ChatKey? = null

        override fun load(): ChatKey? = key

        override fun store(key: ChatKey) {
            this.key = key
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
    }
}
