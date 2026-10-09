package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

internal class CommunityGovernanceReader(
    private val transport: WebDavTransport,
    private val envelopes: CommunityGovernanceEnvelopeCodec,
) {
    suspend fun fetch(
        kind: GovernanceObjectKind,
        communityId: String,
        contentHash: String,
        key: ChatKey,
    ): WebDavResult<ByteArray> {
        if (!GovernanceRecordSupport.isValidCommunityId(communityId) || !CommunityGovernancePaths.isValidContentHash(contentHash)) {
            return WebDavResult.InvalidPath("invalid governance reference")
        }
        val path = CommunityGovernancePaths.objectPath(kind, contentHash)
        return when (val result = transport.readRaw(path)) {
            is WebDavResult.Success -> {
                if (envelopes.contentHash(result.value) != contentHash) {
                    WebDavResult.TransportError(null, "governance file hash mismatch")
                } else {
                    envelopes.open(kind, communityId, key, result.value)?.let { WebDavResult.Success(it) }
                        ?: WebDavResult.TransportError(null, "governance authentication failed")
                }
            }
            else -> governanceFailure(result)
        }
    }

    suspend fun listHashes(kind: GovernanceObjectKind): WebDavResult<List<String>> =
        when (
            val result =
                transport.listBounded(
                    CommunityGovernancePaths.collectionPath(kind),
                    MAX_LIST_ENTRIES,
                    MAX_LIST_RESPONSE_BYTES,
                )
        ) {
            is WebDavResult.Success -> {
                if (result.value.any { it.isCollection }) {
                    WebDavResult.TransportError(null, "nested governance href rejected")
                } else {
                    val hashes =
                        result.value.mapNotNull { entry ->
                            entry.name.takeIf(CommunityGovernancePaths::isValidContentHash)
                        }.distinct().sorted()
                    WebDavResult.Success(hashes)
                }
            }
            else -> governanceFailure(result)
        }

    private companion object {
        const val MAX_LIST_ENTRIES = 1000
        const val MAX_LIST_RESPONSE_BYTES = 256 * 1024
    }
}
