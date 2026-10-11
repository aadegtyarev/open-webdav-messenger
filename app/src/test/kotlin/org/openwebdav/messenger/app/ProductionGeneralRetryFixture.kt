package org.openwebdav.messenger.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockWebServer
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.CommunityRegistry
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.sync.FakeDisk
import org.openwebdav.messenger.sync.SyncTestSupport
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
internal class ProductionGeneralRetryFixture(private val scheduler: TestCoroutineScheduler) {
    val server = MockWebServer()
    val disk = FakeDisk()
    val db: MessengerDatabase = SyncTestSupport.inMemoryDb()
    val identity = AppTestSupport.newIdentity()
    val chatKey = SyncTestSupport.fixedChatKey()
    val chatId = SyncTestSupport.CHAT_ID
    val communityId = "general-retry-${UUID.randomUUID().toString().take(8)}"
    val otherChatId = "other-general"
    val config = SyncTestSupport.config(server)
    val store = SyncTestSupport.store(db, communityId)
    val context: Context = RuntimeEnvironment.getApplication()

    fun start() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        server.dispatcher = disk
        AppContainer.bind(context)
        AppTestSupport.configureGeneralRetryStorage(context, communityId, chatId, otherChatId, config, chatKey)
        EngineWiring.initialize(
            AppTestSupport.chatOpenTestDeps(
                server,
                db,
                communityId,
                StoredConnection(config, chatId, "Community"),
                identity,
                mapOf(chatId to chatKey, otherChatId to chatKey),
            ),
        )
    }

    fun installOtherGeneral() {
        EngineWiring.reconfigure(config, otherChatId, "Other", chatKey, identity, communityId)
    }

    suspend fun close() =
        withContext(NonCancellable) {
            try {
                AppContainer.clearChatOpenTestSeam()
                EngineWiring.initialize(AppTestSupport.emptyEngineDeps())
            } finally {
                CommunityRegistry(context).remove(communityId)
                ChatRegistry(context).clear(communityId)
                Dispatchers.resetMain()
                server.shutdown()
                db.close()
            }
        }
}
