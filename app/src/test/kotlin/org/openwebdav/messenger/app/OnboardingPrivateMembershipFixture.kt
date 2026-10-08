package org.openwebdav.messenger.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.directory.DirectoryFakeDisk
import org.openwebdav.messenger.directory.DirectoryTestSupport
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.membership.PendingPrivateClaimStore
import org.openwebdav.messenger.membership.PrivateClaimPublicationStatus
import org.openwebdav.messenger.membership.PrivateMembershipAead
import org.openwebdav.messenger.membership.PrivateMembershipClaimCodec
import org.openwebdav.messenger.membership.PrivateMembershipClaimCrypto
import org.openwebdav.messenger.membership.PrivateMembershipPublisher
import org.openwebdav.messenger.membership.PrivateMembershipService

/** Test fixture connecting the real onboarding callback to durable claim publication. */
internal class OnboardingPrivateMembershipFixture(context: Context, failPut: Boolean = false) {
    val identity: Identity = AppTestSupport.newIdentity()
    val key: ChatKey = AppTestSupport.keySources().newRandomKey()
    val chatId = "private-chat-integration-0001"
    private val disk = DirectoryFakeDisk().also { if (failPut) it.failPutUnderPrefix["private-membership"] = 503 }
    val server =
        MockWebServer().apply {
            dispatcher = disk
            start()
        }
    val store = PendingPrivateClaimStore(context, AppTestSupport.native()).also { it.clearAll() }
    private val claims =
        PrivateMembershipClaimCrypto(
            PrivateMembershipAead(Aead(AppTestSupport.native())),
            PrivateMembershipClaimCodec(AppTestSupport.identityCrypto()),
        )
    val publisher = PrivateMembershipPublisher(store, claims)
    val service = PrivateMembershipService(DirectoryTestSupport.transport(server), claims)
    val deps = RecordingDeps(identity)

    suspend fun join(): OnboardingService.JoinResult =
        OnboardingService(deps, AppTestSupport.inviteCodec(), Dispatchers.Unconfined)
            .joinFromInvite(
                AppTestSupport.inviteString(AppTestSupport.httpsConfig(), chatId, key, "Private", ChatAccess.PRIVATE),
            )

    fun close() {
        store.clearAll()
        server.shutdown()
    }

    inner class RecordingDeps(val identity: Identity) : OnboardingService.Deps {
        val chatKeyStore = InMemoryChatKeyStore()
        var status: PrivateClaimPublicationStatus? = null
        var generationAtReconfigure: Long? = null

        override fun keySources(): KeySources = AppTestSupport.keySources()

        override fun chatKeyStore() = chatKeyStore

        override fun saveConfig(
            config: org.openwebdav.messenger.transport.ConnectionConfig,
            chatId: String,
            communityName: String,
            access: String,
        ) = Unit

        override suspend fun checkFolder(
            config: org.openwebdav.messenger.transport.ConnectionConfig,
            root: String,
        ) = OnboardingService.FolderCheck.Ok

        override suspend fun ensureIdentity(): Identity = identity

        override fun newChatId(): String = chatId

        override fun reconfigure(
            config: org.openwebdav.messenger.transport.ConnectionConfig,
            chatId: String,
            communityName: String,
            chatKey: ChatKey,
            identity: Identity,
            isHost: Boolean,
        ) {
            generationAtReconfigure = AccountMutationBarrier.process.replacementGeneration()
            status =
                runBlocking {
                    publisher.publish(ChatAccess.PRIVATE, chatId, chatId, "", identity, chatKey, service)
                }
        }
    }
}
