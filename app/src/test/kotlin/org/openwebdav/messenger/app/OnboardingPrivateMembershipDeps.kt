package org.openwebdav.messenger.app

import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.membership.PrivateClaimPublicationStatus
import org.openwebdav.messenger.membership.PrivateMembershipPublisher
import org.openwebdav.messenger.membership.PrivateMembershipService
import org.openwebdav.messenger.transport.ConnectionConfig

internal class OnboardingPrivateMembershipDeps(
    val identity: Identity,
    val chatId: String,
    private val publisher: PrivateMembershipPublisher,
    private val service: PrivateMembershipService,
) : OnboardingService.Deps {
    val chatKeyStore = InMemoryChatKeyStore()
    var status: PrivateClaimPublicationStatus? = null
    var generationAtReconfigure: Long? = null
    var generationAtAfterReplacement: Long? = null
    var afterReplacementPassedStableGate = false
    var onStoreCommit: (() -> Unit)? = null
    var onRuntimeInstall: (() -> Unit)? = null
    private var installedChatKey: ChatKey? = null

    override fun keySources(): KeySources = AppTestSupport.keySources()

    override fun chatKeyStore() = chatKeyStore

    override fun saveConfig(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        access: String,
    ) {
        onStoreCommit?.invoke()
    }

    override suspend fun checkFolder(
        config: ConnectionConfig,
        root: String,
    ) = OnboardingService.FolderCheck.Ok

    override suspend fun ensureIdentity(): Identity = identity

    override fun newChatId(): String = chatId

    override fun reconfigure(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        chatKey: ChatKey,
        identity: Identity,
        isHost: Boolean,
    ) {
        generationAtReconfigure = AccountMutationBarrier.process.replacementGeneration()
        installedChatKey = chatKey
        onRuntimeInstall?.invoke()
    }

    override suspend fun afterAccountReplacement() {
        generationAtAfterReplacement = AccountMutationBarrier.process.replacementGeneration()
        AccountMutationBarrier.process.withStableAccount { afterReplacementPassedStableGate = true }
        status = publisher.publish(ChatAccess.PRIVATE, chatId, chatId, "", identity, checkNotNull(installedChatKey), service)
    }
}
