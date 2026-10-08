package org.openwebdav.messenger.membership

import android.content.Context
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.keystore.StrictFileOperations
import java.io.File

/** Persists the exact encrypted claim for idempotent retry; files are excluded from Android backup. */
internal class PendingPrivateClaimStore(context: Context, native: NativeCrypto) {
    private val directory = File(context.noBackupFilesDir, "private_membership/pending")
    private val pendingContext = PendingPrivateClaimContext(directory, native)
    private val fileWriter = PendingPrivateClaimFileWriter(pendingContext)

    fun load(
        communityId: String,
        chatId: String,
        kind: String,
        key: ChatKey,
        identity: Identity,
    ): PendingPrivateClaim? {
        val file = pendingContext.file(communityId, chatId, identity)
        if (!file.exists()) return null
        if (file.length() > PendingPrivateClaimCodec.MAX_BYTES) {
            runCatching { StrictFileOperations.delete(file) }
            return null
        }
        val decoded =
            PendingPrivateClaimCodec.decode(file.readBytes()) ?: run {
                runCatching { StrictFileOperations.delete(file) }
                return null
            }
        val expected = pendingContext.digest(communityId, chatId, kind, key, identity)
        if (!decoded.second.contentEquals(expected)) {
            runCatching { StrictFileOperations.delete(file) }
            return null
        }
        return decoded.first
    }

    fun savePending(
        communityId: String,
        chatId: String,
        kind: String,
        key: ChatKey,
        identity: Identity,
        claim: ByteArray,
    ) = fileWriter.save(
        communityId,
        chatId,
        identity,
        pendingContext.digest(communityId, chatId, kind, key, identity),
        PendingPrivateClaim(claim.copyOf(), false),
    )

    fun markUploaded(
        communityId: String,
        chatId: String,
        kind: String,
        key: ChatKey,
        identity: Identity,
        claim: ByteArray,
    ) = fileWriter.save(
        communityId,
        chatId,
        identity,
        pendingContext.digest(communityId, chatId, kind, key, identity),
        PendingPrivateClaim(claim.copyOf(), true),
    )

    fun invalidate(
        communityId: String,
        chatId: String,
        identity: Identity,
    ) {
        val target = pendingContext.file(communityId, chatId, identity)
        if (target.exists()) StrictFileOperations.delete(target)
    }

    fun clearAll() {
        directory.listFiles()?.forEach(StrictFileOperations::delete)
    }
}
