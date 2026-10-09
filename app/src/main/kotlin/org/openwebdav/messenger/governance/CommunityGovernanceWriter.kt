package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

internal class CommunityGovernanceWriter(
    private val transport: WebDavTransport,
    private val envelopes: CommunityGovernanceEnvelopeCodec,
) {
    fun seal(
        kind: GovernanceObjectKind,
        communityId: String,
        key: ChatKey,
        plaintext: ByteArray,
    ): GovernanceObjectReference {
        val storedBytes = envelopes.seal(kind, communityId, key, plaintext)
        return GovernanceObjectReference(kind, communityId, envelopes.contentHash(storedBytes), storedBytes)
    }

    suspend fun publish(reference: GovernanceObjectReference): WebDavResult<GovernanceObjectReference> {
        if (!GovernanceRecordSupport.isValidCommunityId(reference.communityId) ||
            !CommunityGovernancePaths.isValidContentHash(reference.contentHash) ||
            envelopes.contentHash(reference.storedBytes) != reference.contentHash
        ) {
            return WebDavResult.TransportError(null, "invalid governance object reference")
        }
        val path = CommunityGovernancePaths.objectPath(reference.kind, reference.contentHash)
        for (collection in CommunityGovernancePaths.requiredCollections(reference.kind)) {
            when (val result = transport.ensureCollection(collection)) {
                is WebDavResult.Success -> Unit
                else -> return governanceFailure(result)
            }
        }
        return when (val result = transport.write(path, reference.storedBytes)) {
            is WebDavResult.Success -> WebDavResult.Success(reference)
            else -> governanceFailure(result)
        }
    }
}
