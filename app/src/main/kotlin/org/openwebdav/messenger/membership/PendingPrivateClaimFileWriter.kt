package org.openwebdav.messenger.membership

import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.keystore.StrictFileOperations
import java.nio.file.Files

/** Writes pending claim records using a unique same-directory temp file and atomic replacement. */
internal class PendingPrivateClaimFileWriter(private val context: PendingPrivateClaimContext) {
    fun save(
        community: String,
        chat: String,
        identity: Identity,
        digest: ByteArray,
        record: PendingPrivateClaim,
    ) {
        val target = context.file(community, chat, identity)
        val parent = target.parentFile ?: error("pending claim directory is unavailable")
        parent.mkdirs()
        val temp = Files.createTempFile(parent.toPath(), ".pending-", ".tmp").toFile()
        try {
            temp.writeBytes(PendingPrivateClaimCodec.encode(digest, record))
            StrictFileOperations.atomicReplace(temp, target)
        } finally {
            if (temp.exists()) runCatching { StrictFileOperations.delete(temp) }
        }
    }
}
