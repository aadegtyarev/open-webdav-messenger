package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.governance.CommunityDmOriginScanner
import org.openwebdav.messenger.governance.CommunityGovernanceEnvelopeCodec
import org.openwebdav.messenger.governance.CommunityGovernanceReader
import org.openwebdav.messenger.governance.CommunityGroupOriginScanner
import org.openwebdav.messenger.identity.IdentityCrypto

internal class ChatDeletionAuthorityProjector(
    private val reader: CommunityGovernanceReader,
    private val envelopes: CommunityGovernanceEnvelopeCodec,
    private val identityCrypto: IdentityCrypto,
) {
    suspend fun project(
        communityId: String,
        chatId: String,
        kind: String,
        signerPublicKey: ByteArray,
        verifiedHost: Boolean,
        communityKey: ChatKey?,
        chatKey: ChatKey?,
    ): ChatDeleteCapability =
        when (kind) {
            "group" -> projectGroup(communityId, chatId, signerPublicKey, verifiedHost, communityKey)
            "dm" -> projectDm(communityId, chatId, signerPublicKey, chatKey)
            else -> ChatDeleteCapability.NotAuthorized
        }

    private suspend fun projectGroup(
        communityId: String,
        chatId: String,
        signerPublicKey: ByteArray,
        verifiedHost: Boolean,
        communityKey: ChatKey?,
    ): ChatDeleteCapability {
        val key = communityKey ?: return ChatDeleteCapability.Unavailable
        return when (val origin = CommunityGroupOriginScanner(reader, identityCrypto).inspect(communityId, chatId, key)) {
            CommunityGroupOriginScanner.Result.Unavailable -> ChatDeleteCapability.Unavailable
            CommunityGroupOriginScanner.Result.Empty, CommunityGroupOriginScanner.Result.Conflict ->
                if (verifiedHost) ChatDeleteCapability.Available else ChatDeleteCapability.NotAuthorized
            is CommunityGroupOriginScanner.Result.Found ->
                if (verifiedHost || origin.origin.creatorSigningPublicKey.contentEquals(signerPublicKey)) {
                    ChatDeleteCapability.Available
                } else {
                    ChatDeleteCapability.NotAuthorized
                }
        }
    }

    private suspend fun projectDm(
        communityId: String,
        chatId: String,
        signerPublicKey: ByteArray,
        chatKey: ChatKey?,
    ): ChatDeleteCapability {
        val key = chatKey ?: return ChatDeleteCapability.Unavailable
        return when (
            val origin = CommunityDmOriginScanner(reader, envelopes, identityCrypto).inspectOrigin(communityId, chatId, key)
        ) {
            CommunityDmOriginScanner.Result.Unavailable -> ChatDeleteCapability.Unavailable
            CommunityDmOriginScanner.Result.Empty, CommunityDmOriginScanner.Result.Conflict -> ChatDeleteCapability.NotAuthorized
            is CommunityDmOriginScanner.Result.Found ->
                if (origin.origin.initiatorSigningPublicKey.contentEquals(signerPublicKey)) {
                    ChatDeleteCapability.Available
                } else {
                    ChatDeleteCapability.NotAuthorized
                }
        }
    }
}
