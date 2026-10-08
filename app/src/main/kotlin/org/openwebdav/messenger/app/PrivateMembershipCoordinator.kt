package org.openwebdav.messenger.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.membership.PrivateMembershipCache
import org.openwebdav.messenger.membership.PrivateMembershipPublisher
import org.openwebdav.messenger.membership.PrivateMembershipService
import org.openwebdav.messenger.membership.VerifiedPrivateMember
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.ui.settings.UserSettings

/** Coordinates private-only roster/cache refresh and durable self-claim publication. */
internal class PrivateMembershipCoordinator(
    private val scope: CoroutineScope,
    private val cache: PrivateMembershipCache,
    private val publisher: PrivateMembershipPublisher,
    private val serviceFor: (ConnectionConfig) -> PrivateMembershipService,
    private val readDirectory: suspend (StoredConnection, ChatKey) -> List<DirectoryEntry>?,
    private val unavailableMessage: String,
) {
    fun prepare(
        graph: RuntimeGraph,
        stored: StoredConnection,
        communityKey: ChatKey?,
        isCurrent: () -> Boolean,
    ) {
        if (!isCurrent()) return
        val accountGeneration = AccountMutationBarrier.process.replacementGeneration()
        graph.enablePrivateMembership()
        val lookup = cache.lookup(graph.communityId, graph.chatId, "private", graph.chatKey, graph.identity)
        val cached = lookup.record?.let { PrivateMembershipCacheProjection.members(graph.chatId, it.members) }
        val cachedReady = cached != null && cache.applyCached(lookup, isCurrent) { applyMembers(graph, cached) }
        if (!cachedReady && isCurrent()) graph.updateRecipientReadiness(RecipientReadiness.Loading)
        val service = serviceFor(stored.config)
        scope.launch {
            val directory = communityKey?.let { runCatching { readDirectory(stored, it) }.getOrNull() }
            val result = runCatching { service.read(graph.chatId, graph.chatKey, directory) }.getOrNull()
            if (!isCurrent()) return@launch
            if (result == null || result.listingFailed) {
                if (!cachedReady) graph.updateRecipientReadiness(RecipientReadiness.Unavailable(unavailableMessage))
                return@launch
            }
            val record =
                PrivateMembershipCacheProjection.record(
                    graph.communityId,
                    graph.chatId,
                    lookup.provenance,
                    result.members,
                )
            try {
                cache.commit(lookup, record, isCurrent) { applyMembers(graph, result.members) }
            } catch (_: Exception) {
                cache.applyLive(lookup, isCurrent) { applyMembers(graph, result.members) }
            }
        }
        scope.launch {
            val status =
                publisher.publish(
                    ChatAccess.PRIVATE,
                    graph.communityId,
                    graph.chatId,
                    UserSettings.displayName,
                    graph.identity,
                    graph.chatKey,
                    service,
                    accountGeneration,
                    isCurrent,
                )
            AccountMutationBarrier.process.withStableAccount {
                if (AccountMutationBarrier.process.replacementGeneration() == accountGeneration && isCurrent()) {
                    graph.updatePrivateClaimStatus(status)
                }
            }
        }
    }

    private fun applyMembers(
        graph: RuntimeGraph,
        members: List<VerifiedPrivateMember>,
    ) {
        val participants = privateVerifiedParticipants(members, graph.senderIdentifier, graph.identity.copySignPublic())
        graph.memberNames = members.associate { Hex.encode(it.claim.copySigningPublicKey()) to it.displayName }
        val ids = (members.map { Hex.encode(it.claim.copySigningPublicKey()) } + graph.senderIdentifier).distinct()
        graph.updateRecipientReadiness(RecipientReadiness.Ready(ids, participants))
    }
}
