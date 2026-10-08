package org.openwebdav.messenger.membership

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.keystore.AccountIdentifier
import org.openwebdav.messenger.protocol.HashTag
import java.io.File

internal class PendingPrivateClaimContext(private val directory: File, private val native: NativeCrypto) {
    fun file(
        community: String,
        chat: String,
        identity: Identity,
    ): File {
        AccountIdentifier.requireValid(community)
        AccountIdentifier.requireValid(chat)
        val name = HashTag.tag("$community\u001f$chat\u001f".toByteArray() + identity.copySignPublic(), 32)
        return File(directory, "$name.bin")
    }

    fun digest(
        community: String,
        chat: String,
        kind: String,
        key: ChatKey,
        identity: Identity,
    ): ByteArray {
        val raw = key.copyBytes()
        try {
            val prefix = "owdm/pending-private-claim/v1\u0000$community\u001f$chat\u001f$kind\u001f".toByteArray()
            return native.genericHash(prefix + raw + identity.copySignPublic() + identity.copyBoxPublic(), 32)
        } finally {
            raw.fill(0)
        }
    }
}
