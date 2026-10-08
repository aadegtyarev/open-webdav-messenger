package org.openwebdav.messenger.app

import okhttp3.mockwebserver.MockWebServer
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.export.ExportPayload
import org.openwebdav.messenger.export.ExportTestSupport
import org.openwebdav.messenger.export.RestoreManager
import org.openwebdav.messenger.export.encryptedExportTestPayload
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.transport.ConnectionConfig

/** JVM harness: real AppContainer open/EngineWiring installs over in-memory Android-store adapters. */
internal class AppContainerRestoreFixture(private val server: MockWebServer) {
    val communityId = "restore-community-shared"
    val chatId = "restore-private-chat-shared"
    val oldIdentity: Identity = AppTestSupport.newIdentity()
    val newIdentity: Identity = AppTestSupport.newIdentity()
    val oldKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 31 })
    val newKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 47 })
    val oldConfig: ConnectionConfig = SyncTestSupport.config(server).copy(username = "old-user")
    val newConfig: ConnectionConfig = oldConfig.copy(username = "new-user")
    val native = ExportTestSupport.native()
    val database: MessengerDatabase = SyncTestSupport.inMemoryDb()
    val configStore = ExportTestSupport.inMemoryConnectionConfigStore().also { it.store(oldConfig) }
    val communityKeyStore = ExportTestSupport.inMemoryCommunityKeyStore()
    val chatKeyStore = ExportTestSupport.inMemoryChatKeyStore().also { it.store(chatId, oldKey) }
    val identityStore = ExportTestSupport.inMemoryIdentityStore().also { it.store(oldIdentity) }
    val oldBackup = appContainerRestoreBackup(communityId, chatId, oldConfig)
    val replacementBackup = appContainerRestoreBackup(communityId, chatId, newConfig)
    val accountStore = ExportTestSupport.InMemoryAccountBackupStore(oldBackup)
    private val chatKeys = mutableMapOf(chatId to oldKey)

    init {
        EngineWiring.initialize(
            AppTestSupport.chatOpenTestDeps(
                server,
                database,
                communityId,
                appContainerRestoreStored(oldBackup.communities.single()),
                oldIdentity,
                chatKeys,
                privateMembershipChat = true,
            ),
        )
        configureRestoreOpenSeam(communityId, accountStore, chatKeyStore)
    }

    fun restoreBlob(): String {
        val payload = ExportPayload.build(null, null, mapOf(chatId to newKey), newIdentity, replacementBackup)
        return encryptedExportTestPayload(native, ExportPayload.toJson(payload), "restore-password")
    }

    fun restoreManager(
        activateRuntime: () -> Unit,
        restorePreviousRuntime: () -> Unit = ::installCurrentAccount,
        afterRuntimeActivated: suspend () -> Unit = {},
        afterRuntimeRestored: suspend () -> Unit = {},
    ) = RestoreManager(
        native,
        configStore,
        communityKeyStore,
        chatKeyStore,
        identityStore,
        accountBackupStore = accountStore,
        activateRuntime = activateRuntime,
        restorePreviousRuntime = restorePreviousRuntime,
        afterRuntimeActivated = afterRuntimeActivated,
        afterRuntimeRestored = afterRuntimeRestored,
    )

    fun installCurrentAccount() {
        val community = checkNotNull(accountStore.snapshot()).communities.single()
        val key = checkNotNull(chatKeyStore.load(community.anchorChatId))
        val identity = (identityStore.load() as IdentityLoadResult.Loaded).identity
        chatKeys[chatId] = key
        EngineWiring.initialize(
            AppTestSupport.chatOpenTestDeps(
                server,
                database,
                communityId,
                appContainerRestoreStored(community),
                identity,
                chatKeys,
                privateMembershipChat = true,
            ),
        )
    }

    fun close() {
        AppContainer.clearChatOpenTestSeam()
        EngineWiring.initialize(AppTestSupport.emptyEngineDeps())
        database.close()
        server.shutdown()
    }
}
