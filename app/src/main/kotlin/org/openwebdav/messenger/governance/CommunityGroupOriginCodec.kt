package org.openwebdav.messenger.governance

import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.protocol.Base32
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object CommunityGroupOriginCodec {
    fun sign(
        origin: CommunityGroupOrigin,
        signingSecret: ByteArray,
        crypto: IdentityCrypto,
    ): ByteArray {
        require(GovernanceRecordSupport.isValidCommunityId(origin.communityId))
        require(ORIGIN_ID.matches(origin.chatId) && origin.creatorSigningPublicKey.size == KEY_BYTES)
        require(origin.creatorBoxPublicKey.size == KEY_BYTES && origin.nonce.size == NONCE_BYTES)
        val body = body(origin)
        return body + crypto.sign(DOMAIN + byteArrayOf(0) + body, signingSecret)
    }

    fun verify(
        bytes: ByteArray,
        communityId: String?,
        chatId: String?,
        crypto: IdentityCrypto,
    ): CommunityGroupOrigin? {
        if (bytes.size !in MIN_BYTES..MAX_BYTES || (communityId != null && !GovernanceRecordSupport.isValidCommunityId(communityId)) ||
            (chatId != null && !ORIGIN_ID.matches(chatId))
        ) {
            return null
        }
        return runCatching {
            DataInputStream(bytes.inputStream()).use { input ->
                require(GovernanceRecordSupport.readExactly(input, MAGIC.size).contentEquals(MAGIC) && input.readUnsignedByte() == VERSION)
                val id = readText(input).also { require(communityId == null || it == communityId) }
                val idChat = readText(input).also { require(chatId == null || it == chatId) }
                val access =
                    when (input.readUnsignedByte()) {
                        0 -> ChatAccess.PUBLIC
                        1 -> ChatAccess.PRIVATE
                        else -> error("invalid group access")
                    }
                val signingKey = GovernanceRecordSupport.readExactly(input, KEY_BYTES).also { require(it.size == KEY_BYTES) }
                val boxKey = GovernanceRecordSupport.readExactly(input, KEY_BYTES).also { require(it.size == KEY_BYTES) }
                val nonce = GovernanceRecordSupport.readExactly(input, NONCE_BYTES).also { require(it.size == NONCE_BYTES) }
                val signature = GovernanceRecordSupport.readExactly(input, SIGNATURE_BYTES).also { require(it.size == SIGNATURE_BYTES) }
                require(input.available() == 0)
                val body = bytes.copyOfRange(0, bytes.size - SIGNATURE_BYTES)
                require(crypto.verify(signature, DOMAIN + byteArrayOf(0) + body, signingKey))
                CommunityGroupOrigin(id, idChat, access, signingKey, boxKey, nonce)
            }
        }.getOrNull()
    }

    fun contentAddress(bytes: ByteArray): String =
        Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_CHARS)

    private fun body(origin: CommunityGroupOrigin): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeByte(VERSION)
                writeText(output, origin.communityId)
                writeText(output, origin.chatId)
                output.writeByte(if (origin.access == ChatAccess.PUBLIC) 0 else 1)
                output.write(origin.creatorSigningPublicKey)
                output.write(origin.creatorBoxPublicKey)
                output.write(origin.nonce)
            }
        }.toByteArray()

    private fun writeText(
        output: DataOutputStream,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readUnsignedShort().also { require(it in 1..MAX_TEXT_BYTES) }
        return GovernanceRecordSupport.strictUtf8(GovernanceRecordSupport.readExactly(input, size).also { require(it.size == size) })
    }

    private val MAGIC = "OWGO".toByteArray(Charsets.US_ASCII)
    private val DOMAIN = "owdm/community-group-origin/v1".toByteArray(Charsets.UTF_8)
    private val ORIGIN_ID = Regex("[A-Za-z0-9_-]{1,96}")
    private const val VERSION = 1
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 32
    private const val SIGNATURE_BYTES = 64
    private const val MAX_TEXT_BYTES = 96
    private const val MIN_BYTES = 4 + 1 + 2 + 1 + 2 + 1 + 1 + 32 + 32 + 32 + 64
    private const val MAX_BYTES = MIN_BYTES + MAX_TEXT_BYTES * 2
    private const val HASH_CHARS = 32
}
