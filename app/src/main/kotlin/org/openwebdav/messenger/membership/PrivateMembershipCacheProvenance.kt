package org.openwebdav.messenger.membership

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import java.io.ByteArrayOutputStream

internal class PrivateMembershipCacheProvenance(private val native: NativeCrypto) {
    fun digest(
        community: String,
        chat: String,
        kind: String,
        key: ChatKey,
        identity: Identity,
    ): ByteArray {
        val rawKey = key.copyBytes()
        try {
            val out = ByteArrayOutputStream()
            listOf(DOMAIN, community, chat, kind).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.write(bytes.size shr 8)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(rawKey)
            out.write(identity.copySignPublic())
            out.write(identity.copyBoxPublic())
            return native.genericHash(out.toByteArray(), DIGEST_BYTES)
        } finally {
            rawKey.fill(0)
        }
    }

    private companion object {
        const val DOMAIN = "owdm/private-roster-cache/v1"
        const val DIGEST_BYTES = 32
    }
}
