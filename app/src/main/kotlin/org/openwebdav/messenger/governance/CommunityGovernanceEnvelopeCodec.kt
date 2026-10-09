package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.OpenResult
import org.openwebdav.messenger.protocol.Base32
import java.security.MessageDigest

internal class CommunityGovernanceEnvelopeCodec(private val aead: Aead) {
    fun seal(
        kind: GovernanceObjectKind,
        communityId: String,
        key: ChatKey,
        plaintext: ByteArray,
    ): ByteArray {
        require(GovernanceRecordSupport.isValidCommunityId(communityId))
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val header = magic + byteArrayOf(VERSION, kind.wireValue)
        return header + aead.sealWithAssociatedData(key, associatedData(kind, communityId), plaintext)
    }

    fun open(
        kind: GovernanceObjectKind,
        communityId: String,
        key: ChatKey,
        storedBytes: ByteArray,
    ): ByteArray? {
        if (!GovernanceRecordSupport.isValidCommunityId(communityId) ||
            storedBytes.size !in MIN_FILE_BYTES..MAX_FILE_BYTES ||
            !storedBytes.copyOfRange(0, magic.size).contentEquals(magic) ||
            storedBytes[magic.size] != VERSION || storedBytes[magic.size + 1] != kind.wireValue
        ) {
            return null
        }
        return when (
            val opened =
                aead.openWithAssociatedData(
                    key,
                    associatedData(kind, communityId),
                    storedBytes.copyOfRange(HEADER_BYTES, storedBytes.size),
                )
        ) {
            is OpenResult.Opened -> opened.bytes.takeIf { it.size <= MAX_PLAINTEXT_BYTES }
            OpenResult.Rejected -> null
        }
    }

    fun contentHash(storedBytes: ByteArray): String =
        Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(storedBytes)).take(HASH_CHARS)

    private fun associatedData(
        kind: GovernanceObjectKind,
        communityId: String,
    ): ByteArray {
        val id = communityId.toByteArray(Charsets.UTF_8)
        return domain + byteArrayOf(0, VERSION, kind.wireValue) + byteArrayOf((id.size shr 8).toByte(), id.size.toByte()) + id
    }

    private val magic = "OWGV".toByteArray(Charsets.US_ASCII)
    private val domain = "owdm/community-governance/aead".toByteArray(Charsets.UTF_8)

    private companion object {
        const val VERSION = 1.toByte()
        const val HEADER_BYTES = 6
        const val MIN_FILE_BYTES = HEADER_BYTES + Aead.NONCE_BYTES + Aead.TAG_BYTES
        const val MAX_FILE_BYTES = 64 * 1024
        const val MAX_PLAINTEXT_BYTES = MAX_FILE_BYTES - MIN_FILE_BYTES
        const val HASH_CHARS = 32
    }
}
