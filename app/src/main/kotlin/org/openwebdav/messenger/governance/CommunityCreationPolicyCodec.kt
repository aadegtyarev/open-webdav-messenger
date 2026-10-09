package org.openwebdav.messenger.governance

import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.protocol.Base32
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object CommunityCreationPolicyCodec {
    fun sign(
        communityId: String,
        revision: Long,
        allowPublic: Boolean,
        allowPrivate: Boolean,
        grandfatheredOriginHashes: List<String>,
        signingSecret: ByteArray,
        crypto: IdentityCrypto,
    ): ByteArray {
        require(GovernanceRecordSupport.isValidCommunityId(communityId) && revision > 0)
        require(grandfatheredOriginHashes.size <= MAX_ORIGINS)
        require(grandfatheredOriginHashes.all(HASH::matches))
        require(grandfatheredOriginHashes.distinct().size == grandfatheredOriginHashes.size)
        val body = body(communityId, revision, allowPublic, allowPrivate, grandfatheredOriginHashes.sorted())
        return body + crypto.sign(domainInput(body), signingSecret)
    }

    fun verify(
        bytes: ByteArray,
        expectedCommunityId: String,
        pinnedHostSigningKey: ByteArray,
        crypto: IdentityCrypto,
    ): CommunityCreationPolicy? {
        if (bytes.size < SIGNATURE_BYTES || bytes.size > MAX_BYTES || pinnedHostSigningKey.size != 32) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val body = bytes.copyOfRange(0, bytes.size - SIGNATURE_BYTES)
                require(input.readNBytes(MAGIC.size).contentEquals(MAGIC))
                require(input.readUnsignedByte() == VERSION)
                val idSize = input.readUnsignedShort().also { require(it in 1..GovernanceRecordSupport.MAX_COMMUNITY_ID_BYTES) }
                val id = GovernanceRecordSupport.strictUtf8(input.readNBytes(idSize))
                require(id == expectedCommunityId && GovernanceRecordSupport.isValidCommunityId(id))
                val revision = input.readLong().also { require(it > 0) }
                val allowPublic = input.readBoolean()
                val allowPrivate = input.readBoolean()
                val count = input.readUnsignedShort().also { require(it <= MAX_ORIGINS) }
                val hashes =
                    List(count) { input.readNBytes(HASH_BYTES).also { require(it.size == HASH_BYTES) }.toString(Charsets.US_ASCII) }
                require(hashes.all(HASH::matches) && hashes == hashes.distinct().sorted())
                val signature = input.readNBytes(SIGNATURE_BYTES).also { require(it.size == SIGNATURE_BYTES) }
                require(input.available() == 0 && crypto.verify(signature, domainInput(body), pinnedHostSigningKey))
                CommunityCreationPolicy(id, revision, allowPublic, allowPrivate, hashes, signature)
            }
        }.getOrNull()
    }

    fun contentAddress(bytes: ByteArray): String =
        Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_CHARS)

    private fun body(
        id: String,
        revision: Long,
        public: Boolean,
        private: Boolean,
        hashes: List<String>,
    ): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeByte(VERSION)
                output.writeShort(idBytes.size)
                output.write(idBytes)
                output.writeLong(revision)
                output.writeBoolean(public)
                output.writeBoolean(private)
                output.writeShort(hashes.size)
                hashes.forEach { output.write(it.toByteArray(Charsets.US_ASCII)) }
            }
        }.toByteArray()
    }

    private fun domainInput(body: ByteArray): ByteArray = DOMAIN + byteArrayOf(0) + body

    private val MAGIC = "OWCP".toByteArray(Charsets.US_ASCII)
    private val DOMAIN = "owdm/community-creation-policy/v1".toByteArray(Charsets.UTF_8)
    private val HASH = Regex("[a-z2-7]{32}")
    private const val VERSION = 1
    private const val HASH_BYTES = 32
    private const val SIGNATURE_BYTES = 64
    private const val MAX_ORIGINS = 1024
    private const val HASH_CHARS = 32
    private const val MAX_BYTES = 4 + 1 + 2 + 96 + 8 + 2 + 2 + MAX_ORIGINS * HASH_BYTES + SIGNATURE_BYTES
}
