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
        if (!GovernanceRecordSupport.isValidCommunityId(communityId)) return WebDavResult.InvalidPath("invalid community")
        return when (val stored = readStoredBytes(kind, contentHash)) {
            is WebDavResult.Success ->
                envelopes.open(kind, communityId, key, stored.value)?.let { WebDavResult.Success(it) }
                    ?: WebDavResult.TransportError(null, "governance authentication failed")
            else -> governanceFailure(stored)
        }
    }

    suspend fun readStoredBytes(
        kind: GovernanceObjectKind,
        contentHash: String,
    ): WebDavResult<ByteArray> {
        if (!CommunityGovernancePaths.isValidContentHash(contentHash)) return WebDavResult.InvalidPath("invalid governance hash")
        val path = CommunityGovernancePaths.objectPath(kind, contentHash)
        return when (val result = transport.readRawBounded(path, MAX_GOVERNANCE_FILE_BYTES)) {
            is WebDavResult.Success ->
                if (envelopes.contentHash(result.value) == contentHash) {
                    result
                } else {
                    WebDavResult.TransportError(
                        null,
                        "governance file hash mismatch",
                    )
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
            is WebDavResult.TransportError ->
                if (result.code == 404) WebDavResult.Success(emptyList()) else governanceFailure(result)
            else -> governanceFailure(result)
        }

    private companion object {
        const val MAX_GOVERNANCE_FILE_BYTES = 64 * 1024
        const val MAX_LIST_ENTRIES = 1000
        const val MAX_LIST_RESPONSE_BYTES = 256 * 1024
    }
}
