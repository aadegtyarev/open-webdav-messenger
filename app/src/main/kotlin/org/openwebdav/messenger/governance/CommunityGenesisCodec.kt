package org.openwebdav.messenger.governance

import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.protocol.Base32
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object CommunityGenesisCodec {
    fun sign(
        communityId: String,
        hostSigningPublicKey: ByteArray,
        hostBoxPublicKey: ByteArray,
        nonce: ByteArray,
        signingSecret: ByteArray,
        crypto: IdentityCrypto,
    ): ByteArray {
        require(GovernanceRecordSupport.isValidCommunityId(communityId))
        require(hostSigningPublicKey.size == PUBLIC_KEY_BYTES && hostBoxPublicKey.size == PUBLIC_KEY_BYTES)
        require(nonce.size == NONCE_BYTES)
        val body = canonicalBody(communityId, hostSigningPublicKey, hostBoxPublicKey, nonce)
        val signature = crypto.sign(domainSeparated(body), signingSecret)
        return body + signature
    }

    fun verify(
        bytes: ByteArray,
        expectedCommunityId: String,
        crypto: IdentityCrypto,
    ): CommunityGenesis? {
        if (bytes.size !in MIN_RECORD_BYTES..MAX_RECORD_BYTES ||
            !GovernanceRecordSupport.isValidCommunityId(expectedCommunityId)
        ) {
            return null
        }
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val bodyLength = bytes.size - SIGNATURE_BYTES
                val body = bytes.copyOfRange(0, bodyLength)
                require(GovernanceRecordSupport.readExactly(input, MAGIC.size).contentEquals(MAGIC))
                require(input.readUnsignedByte() == VERSION)
                val idLength = input.readUnsignedShort()
                require(idLength in 1..GovernanceRecordSupport.MAX_COMMUNITY_ID_BYTES)
                val idBytes = GovernanceRecordSupport.readExactly(input, idLength).also { require(it.size == idLength) }
                val communityId = GovernanceRecordSupport.strictUtf8(idBytes)
                require(communityId == expectedCommunityId && GovernanceRecordSupport.isValidCommunityId(communityId))
                val hostSigningKey =
                    GovernanceRecordSupport.readExactly(
                        input,
                        PUBLIC_KEY_BYTES,
                    ).also { require(it.size == PUBLIC_KEY_BYTES) }
                val hostBoxKey = GovernanceRecordSupport.readExactly(input, PUBLIC_KEY_BYTES).also { require(it.size == PUBLIC_KEY_BYTES) }
                val nonce = GovernanceRecordSupport.readExactly(input, NONCE_BYTES).also { require(it.size == NONCE_BYTES) }
                val signature = GovernanceRecordSupport.readExactly(input, SIGNATURE_BYTES).also { require(it.size == SIGNATURE_BYTES) }
                require(input.available() == 0)
                require(crypto.verify(signature, domainSeparated(body), hostSigningKey))
                CommunityGenesis(communityId, hostSigningKey, hostBoxKey, nonce, signature)
            }
        }.getOrNull()
    }

    fun contentAddress(bytes: ByteArray): String =
        Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_CHARS)

    private fun canonicalBody(
        communityId: String,
        signingKey: ByteArray,
        boxKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        val idBytes = communityId.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC)
                out.writeByte(VERSION)
                out.writeShort(idBytes.size)
                out.write(idBytes)
                out.write(signingKey)
                out.write(boxKey)
                out.write(nonce)
            }
        }.toByteArray()
    }

    private fun domainSeparated(body: ByteArray): ByteArray = SIGNATURE_DOMAIN + byteArrayOf(0) + body

    private val MAGIC = "OWGN".toByteArray(Charsets.US_ASCII)
    private val SIGNATURE_DOMAIN = "owdm/community-genesis/v1".toByteArray(Charsets.UTF_8)
    private const val VERSION = 1
    private const val PUBLIC_KEY_BYTES = 32
    private const val NONCE_BYTES = 32
    private const val SIGNATURE_BYTES = 64
    private const val MIN_RECORD_BYTES = 4 + 1 + 2 + 1 + 32 + 32 + 32 + 64
    private const val MAX_RECORD_BYTES = 4 + 1 + 2 + GovernanceRecordSupport.MAX_COMMUNITY_ID_BYTES + 32 + 32 + 32 + 64
    private const val HASH_CHARS = 32
}
