package org.openwebdav.messenger.membership

import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.message.BigEndian
import org.openwebdav.messenger.message.ByteCursor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Canonical binary claim codec; all parse/verify failures are typed drops. */
internal class PrivateMembershipClaimCodec(private val identityCrypto: IdentityCrypto) {
    fun sign(
        claim: PrivateMembershipClaim,
        signingSecret: ByteArray,
    ): ByteArray {
        val unsigned = serializeUnsigned(claim)
        val signature = identityCrypto.sign(signatureInput(unsigned), signingSecret)
        require(signature.size == PrivateMembershipFormat.SIGNATURE_BYTES)
        return (unsigned + signature).also { require(it.size <= PrivateMembershipFormat.MAX_CLAIM_BYTES) }
    }

    fun parseAndVerify(bytes: ByteArray): ClaimParseResult {
        if (bytes.size !in (PrivateMembershipFormat.CLAIM_HEADER_BYTES + 32 + 64)..PrivateMembershipFormat.MAX_CLAIM_BYTES) {
            return ClaimParseResult.Rejected
        }
        val signatureStart = bytes.size - PrivateMembershipFormat.SIGNATURE_BYTES
        val cursor = ByteCursor(bytes, signatureStart)
        val magic = cursor.take(4) ?: return ClaimParseResult.Rejected
        if (!magic.contentEquals(PrivateMembershipFormat.MAGIC.toByteArray())) return ClaimParseResult.Rejected
        if (cursor.u8()?.toByte() != PrivateMembershipFormat.VERSION) return ClaimParseResult.Rejected
        val chatLength = cursor.u16() ?: return ClaimParseResult.Rejected
        if (chatLength !in 1..PrivateMembershipFormat.MAX_CHAT_ID_BYTES) return ClaimParseResult.Rejected
        val chatBytes = cursor.take(chatLength) ?: return ClaimParseResult.Rejected
        val signing = cursor.take(PrivateMembershipFormat.SIGNING_KEY_BYTES) ?: return ClaimParseResult.Rejected
        val box = cursor.take(PrivateMembershipFormat.BOX_KEY_BYTES) ?: return ClaimParseResult.Rejected
        val nameLength = cursor.u16() ?: return ClaimParseResult.Rejected
        if (nameLength > PrivateMembershipFormat.MAX_DISPLAY_NAME_BYTES) return ClaimParseResult.Rejected
        val nameBytes = cursor.take(nameLength) ?: return ClaimParseResult.Rejected
        if (cursor.pos != signatureStart) return ClaimParseResult.Rejected
        val chatId = decodeUtf8(chatBytes) ?: return ClaimParseResult.Rejected
        val decodedName = decodeUtf8(nameBytes) ?: return ClaimParseResult.Rejected
        val name = canonicalDisplayName(decodedName)
        if (!validChatId(chatId) || name.toByteArray(Charsets.UTF_8).size > PrivateMembershipFormat.MAX_DISPLAY_NAME_BYTES) {
            return ClaimParseResult.Rejected
        }
        val signature = bytes.copyOfRange(signatureStart, bytes.size)
        if (!identityCrypto.verify(signature, signatureInput(bytes.copyOfRange(0, signatureStart)), signing)) {
            return ClaimParseResult.Rejected
        }
        return ClaimParseResult.Verified(PrivateMembershipClaim(chatId, name, signing, box))
    }

    private fun serializeUnsigned(claim: PrivateMembershipClaim): ByteArray {
        val chat = claim.chatId.toByteArray(Charsets.UTF_8)
        val name = canonicalDisplayName(claim.displayName).toByteArray(Charsets.UTF_8)
        require(validChatId(claim.chatId) && chat.size <= PrivateMembershipFormat.MAX_CHAT_ID_BYTES)
        require(name.size <= PrivateMembershipFormat.MAX_DISPLAY_NAME_BYTES)
        val out = ByteArrayOutputStream()
        out.write(PrivateMembershipFormat.MAGIC.toByteArray())
        out.write(PrivateMembershipFormat.VERSION.toInt())
        BigEndian.writeUint16Be(out, chat.size)
        out.write(chat)
        out.write(claim.copySigningPublicKey())
        out.write(claim.copyBoxPublicKey())
        BigEndian.writeUint16Be(out, name.size)
        out.write(name)
        return out.toByteArray()
    }

    private fun canonicalDisplayName(value: String): String = value.trim { it.isWhitespace() }

    private fun signatureInput(unsigned: ByteArray): ByteArray = PrivateMembershipFormat.DOMAIN.toByteArray() + byteArrayOf(0) + unsigned

    private fun validChatId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,96}"))

    private fun decodeUtf8(bytes: ByteArray): String? =
        runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(
                CodingErrorAction.REPORT,
            ).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
}

internal sealed interface ClaimParseResult {
    data class Verified(val claim: PrivateMembershipClaim) : ClaimParseResult

    data object Rejected : ClaimParseResult
}
