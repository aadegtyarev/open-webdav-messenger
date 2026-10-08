package org.openwebdav.messenger.membership

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.transport.ReadResult
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/** Append-only remote membership I/O for exactly one private chat-root capability. */
internal class PrivateMembershipService(
    private val transport: WebDavTransport,
    private val claims: PrivateMembershipClaimCrypto,
) {
    suspend fun publishSelf(
        fileBytes: ByteArray,
        chatId: String,
    ): MembershipPublishOutcome {
        if (fileBytes.size !in 1..PrivateMembershipFormat.MAX_FILE_BYTES) return MembershipPublishOutcome.Failed("claim exceeds bounds")
        val collection =
            runCatching { PrivateMembershipPaths.collection(chatId) }.getOrElse {
                return MembershipPublishOutcome.Failed("invalid private chat context")
            }
        if (transport.ensureCollection(PrivateMembershipFormat.COLLECTION) !is WebDavResult.Success ||
            transport.ensureCollection(collection) !is WebDavResult.Success
        ) {
            return MembershipPublishOutcome.Failed("collection unavailable")
        }
        val name = PrivateMembershipPaths.entryName(fileBytes)
        return when (transport.write(PrivateMembershipPaths.entryPath(chatId, name), fileBytes)) {
            is WebDavResult.Success -> MembershipPublishOutcome.Published
            else -> MembershipPublishOutcome.Failed("claim write failed")
        }
    }

    suspend fun read(
        chatId: String,
        chatKey: ChatKey,
        directoryEntries: List<DirectoryEntry>?,
    ): PrivateMembershipReadResult {
        val collection =
            runCatching { PrivateMembershipPaths.collection(chatId) }.getOrNull()
                ?: return PrivateMembershipReadResult(emptyList(), 0, true)
        val listed = transport.listBounded(collection, PrivateMembershipFormat.MAX_LISTED_ENTRIES, MAX_LISTING_BYTES)
        if (listed !is WebDavResult.Success) return PrivateMembershipReadResult(emptyList(), 0, true)
        val decoded = mutableListOf<PrivateMembershipClaim>()
        var rejected = 0
        var incomplete = false
        for (entry in listed.value) {
            if (entry.isCollection || !PrivateMembershipPaths.isWellFormedEntryName(entry.name)) {
                rejected++
                continue
            }
            when (
                val read =
                    transport.readContentAddressedBounded(
                        PrivateMembershipPaths.entryPath(chatId, entry.name),
                        entry.name,
                        PrivateMembershipFormat.MAX_FILE_BYTES,
                    )
            ) {
                is WebDavResult.Success ->
                    when (val content = read.value) {
                        is ReadResult.Ready -> {
                            val envelope = Envelope.frame(content.codecId, content.blob)
                            when (val verified = claims.open(envelope, chatId, chatKey)) {
                                is ClaimParseResult.Verified -> decoded += verified.claim
                                ClaimParseResult.Rejected -> rejected++
                            }
                        }
                        ReadResult.NotReady -> {
                            rejected++
                            incomplete = true
                        }
                    }
                else -> {
                    rejected++
                    incomplete = true
                }
            }
        }
        if (incomplete) return PrivateMembershipReadResult(emptyList(), rejected, true)
        val resolved = PrivateMembershipResolver.resolve(decoded, directoryEntries)
        return PrivateMembershipReadResult(resolved.members, rejected + resolved.rejectedCount, false)
    }

    private companion object {
        const val MAX_LISTING_BYTES = 256 * 1024
    }
}
