package org.openwebdav.messenger.app

import android.content.Context
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.membership.PendingPrivateClaimStore
import org.openwebdav.messenger.membership.PrivateClaimPublicationStatus
import org.openwebdav.messenger.membership.PrivateMembershipAead
import org.openwebdav.messenger.membership.PrivateMembershipClaimCodec
import org.openwebdav.messenger.membership.PrivateMembershipClaimCrypto
import org.openwebdav.messenger.membership.PrivateMembershipPublisher
import org.openwebdav.messenger.membership.PrivateMembershipService
import org.openwebdav.messenger.transport.TransportFactory

internal class RestoredPrivateClaimPublication(context: Context, native: NativeCrypto) {
    private val pending = PendingPrivateClaimStore(context, native).also { it.clearAll() }
    private val claims =
        PrivateMembershipClaimCrypto(PrivateMembershipAead(Aead(native)), PrivateMembershipClaimCodec(AppTestSupport.identityCrypto()))
    private val publisher = PrivateMembershipPublisher(pending, claims)
    var previousClaimCount = -1
    var firstSigner: ByteArray? = null
    var status: PrivateClaimPublicationStatus? = null

    suspend fun publish(
        graph: RuntimeGraph,
        communityId: String,
        chatId: String,
    ) {
        val service = PrivateMembershipService(TransportFactory.create(graph.config), claims)
        previousClaimCount = service.read(chatId, graph.chatKey, null).members.size
        status = publisher.publish(ChatAccess.PRIVATE, communityId, chatId, "", graph.identity, graph.chatKey, service)
        firstSigner = service.read(chatId, graph.chatKey, null).members.single().claim.copySigningPublicKey()
    }

    fun clear() = pending.clearAll()
}
