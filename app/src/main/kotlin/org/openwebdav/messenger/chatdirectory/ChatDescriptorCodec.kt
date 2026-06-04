package org.openwebdav.messenger.chatdirectory

import org.openwebdav.messenger.identity.IdentityCrypto
import java.io.ByteArrayOutputStream

/**
 * Serializes a chat descriptor to the §11.3 inner signed-payload bytes and applies / verifies the
 * §11.3 Ed25519 signature (`docs/protocol/webdav-layout.md`). The output is exactly the plaintext fed
 * to / returned by the §11.2 community-key AEAD seal/open (mirrors the §10.3 `DirectoryEntryCodec`, one
 * idiom across both community-directory inner formats).
 *
 * Strictly **reject-don't-guess** (§11.3): every length prefix is validated against the buffer, no
 * `!!` is used, no index/bounds exception escapes — every failure is a typed [ChatParseResult]. The
 * **content-level privacy gate is enforced here**: a `dm`-kind entry (§11.5) and an out-of-enum
 * `kind`/`access` are typed rejections on the parse path, dropped at read, never surfaced. The
 * signature is verified LAST, after the structure is fully valid, over the exact `[0 .. len-64)`
 * signed range against the embedded `signing-pubkey`.
 */
internal class ChatDescriptorCodec(private val identity: IdentityCrypto) {
    /**
     * Build the §11.3 signed payload for the descriptor, Ed25519-sign that exact range with
     * [signingSecret] (§11.3), and append the 64-byte signature. Returns the complete §11.3 inner
     * payload ready for the §11.2 AEAD seal.
     *
     * `kind = GROUP` only — a `dm` kind is rejected at the publish call site (the service), never
     * serialized; this method is reached only for a valid group descriptor.
     *
     * @throws IllegalArgumentException on a programming error at the publish call site (over-cap chat-id
     *   or title, wrong-width signing key, negative version-counter, a non-group kind). The read path
     *   never throws; an invalid value arriving FROM disk is a typed parse rejection (see [parse]).
     */
    fun signAndSerialize(
        chatId: ByteArray,
        kind: ChatKind,
        access: ChatAccess,
        title: String,
        signingPublic: ByteArray,
        versionCounter: Long,
        signingSecret: ByteArray,
    ): ByteArray {
        require(kind == ChatKind.GROUP) { "only a group chat descriptor is publishable (dm is never written)" }
        require(signingPublic.size == ChatDescriptorFormat.SIGNING_PUBKEY_BYTES) { "signing-pubkey must be 32 bytes" }
        require(versionCounter >= 0) { "version-counter must be non-negative" }
        require(chatId.size <= ChatDescriptorFormat.MAX_CHAT_ID_BYTES) {
            "chat-id exceeds ${ChatDescriptorFormat.MAX_CHAT_ID_BYTES} bytes"
        }
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        require(titleBytes.size <= ChatDescriptorFormat.MAX_TITLE_BYTES) {
            "title exceeds ${ChatDescriptorFormat.MAX_TITLE_BYTES} UTF-8 bytes"
        }
        val signedPayload = serializeSignedPayload(chatId, access, titleBytes, signingPublic, versionCounter)
        val signature = identity.sign(signedPayload, signingSecret)
        check(signature.size == ChatDescriptorFormat.SIGNATURE_BYTES) {
            "Ed25519 detached signature must be ${ChatDescriptorFormat.SIGNATURE_BYTES} bytes"
        }
        return signedPayload + signature
    }

    /** §11.3: the signed-payload byte range (everything before the trailing 64-byte signature). */
    private fun serializeSignedPayload(
        chatId: ByteArray,
        access: ChatAccess,
        titleBytes: ByteArray,
        signingPublic: ByteArray,
        versionCounter: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream(ChatDescriptorFormat.PREFIX_BEFORE_CHAT_ID_BYTES + chatId.size + titleBytes.size)
        out.write(ChatDescriptorFormat.ENTRY_VERSION.toInt())
        out.write(signingPublic)
        writeUint64Be(out, versionCounter)
        out.write(ChatDescriptorFormat.KIND_GROUP)
        out.write(encodeAccess(access))
        writeUint16Be(out, chatId.size)
        out.write(chatId)
        writeUint16Be(out, titleBytes.size)
        out.write(titleBytes)
        return out.toByteArray()
    }

    /**
     * §11.3/§11.6: parse [payload] (the AEAD-opened inner bytes) into a verified [ParsedChatDescriptor],
     * or a typed [ChatParseResult.Rejected] on any failure. Never throws.
     *
     * The **content-level privacy gate** lives here: a `dm`-kind value (or any non-group kind) is a
     * [ChatRejectReason.DM_OR_INVALID_KIND] rejection (DMs are never surfaced — §11.5); an out-of-enum
     * `access` is [ChatRejectReason.INVALID_ACCESS]. `kind`/`access` sit before the variable fields so a
     * `dm`/invalid-access entry is rejected before the chat-id/title are read (cheap fail-fast).
     */
    fun parse(payload: ByteArray): ChatParseResult {
        if (payload.size < ChatDescriptorFormat.MIN_PAYLOAD_BYTES) {
            return reject(ChatRejectReason.MALFORMED)
        }
        val signatureStart = payload.size - ChatDescriptorFormat.SIGNATURE_BYTES
        val cursor = Cursor(payload, signatureStart)

        val version = cursor.u8() ?: return reject(ChatRejectReason.MALFORMED)
        if (version.toByte() != ChatDescriptorFormat.ENTRY_VERSION) return reject(ChatRejectReason.UNKNOWN_VERSION)
        val signingPub = cursor.take(ChatDescriptorFormat.SIGNING_PUBKEY_BYTES) ?: return reject(ChatRejectReason.MALFORMED)
        val versionCounter = cursor.u64() ?: return reject(ChatRejectReason.MALFORMED)

        val kindByte = cursor.u8() ?: return reject(ChatRejectReason.MALFORMED)
        // §11.5 content-level privacy gate: a dm-kind (or any non-group) is dropped — DMs are never
        // surfaced. This is the read-time enforcement of the DM-never-discoverable invariant.
        if (kindByte != ChatDescriptorFormat.KIND_GROUP) return reject(ChatRejectReason.DM_OR_INVALID_KIND)
        val accessByte = cursor.u8() ?: return reject(ChatRejectReason.MALFORMED)
        val access = decodeAccess(accessByte) ?: return reject(ChatRejectReason.INVALID_ACCESS)

        val chatIdLen = cursor.u16() ?: return reject(ChatRejectReason.MALFORMED)
        if (chatIdLen > ChatDescriptorFormat.MAX_CHAT_ID_BYTES) return reject(ChatRejectReason.MALFORMED)
        val chatId = cursor.take(chatIdLen) ?: return reject(ChatRejectReason.MALFORMED)
        val titleLen = cursor.u16() ?: return reject(ChatRejectReason.MALFORMED)
        if (titleLen > ChatDescriptorFormat.MAX_TITLE_BYTES) return reject(ChatRejectReason.MALFORMED)
        val titleBytes = cursor.take(titleLen) ?: return reject(ChatRejectReason.MALFORMED)
        // The title MUST end exactly at the start of the 64-byte signature: no trailing bytes between
        // the last field and the signature (§11.3 closed layout). An under/overrun is a reject.
        if (cursor.pos != signatureStart) return reject(ChatRejectReason.MALFORMED)

        // Verify LAST, over the exact signed range, against the embedded signing-pubkey (§11.3 hard
        // reject on libsodium -1). The signing-pubkey is inside the signed range, so it cannot be
        // swapped without breaking the signature.
        val signedPayload = payload.copyOfRange(0, signatureStart)
        val signature = payload.copyOfRange(signatureStart, payload.size)
        if (!identity.verify(signature, signedPayload, signingPub)) {
            return reject(ChatRejectReason.BAD_SIGNATURE)
        }
        val title = titleBytes.toString(Charsets.UTF_8)
        return ChatParseResult.Parsed(ParsedChatDescriptor(chatId, ChatKind.GROUP, access, title, signingPub, versionCounter))
    }

    private fun encodeAccess(access: ChatAccess): Int =
        when (access) {
            ChatAccess.PUBLIC -> ChatDescriptorFormat.ACCESS_PUBLIC
            ChatAccess.PRIVATE -> ChatDescriptorFormat.ACCESS_PRIVATE
        }

    private fun decodeAccess(byte: Int): ChatAccess? =
        when (byte) {
            ChatDescriptorFormat.ACCESS_PUBLIC -> ChatAccess.PUBLIC
            ChatDescriptorFormat.ACCESS_PRIVATE -> ChatAccess.PRIVATE
            else -> null
        }

    private fun reject(reason: ChatRejectReason): ChatParseResult = ChatParseResult.Rejected(reason)

    private fun writeUint16Be(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeUint64Be(
        out: ByteArrayOutputStream,
        value: Long,
    ) {
        for (i in 7 downTo 0) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    /**
     * A bounds-checked forward reader over the inner payload, bounded at [limit] (the start of the
     * trailing signature) so a read never crosses into the signature bytes. Every read validates the
     * span against [limit] and returns `null` on overrun instead of throwing (§11.3 reject-don't-guess;
     * stack-notes Kotlin null-safety: no `!!` on parse paths). Big-endian. Mirrors the §10.3 `Cursor`.
     */
    private class Cursor(private val buf: ByteArray, private val limit: Int) {
        var pos: Int = 0
            private set

        private val remaining: Int get() = limit - pos

        fun take(n: Int): ByteArray? {
            if (n < 0 || n > remaining) return null
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun u8(): Int? {
            if (remaining < 1) return null
            return buf[pos++].toInt() and 0xFF
        }

        fun u16(): Int? {
            if (remaining < 2) return null
            val hi = buf[pos].toInt() and 0xFF
            val lo = buf[pos + 1].toInt() and 0xFF
            pos += 2
            return (hi shl 8) or lo
        }

        fun u64(): Long? {
            if (remaining < 8) return null
            var result = 0L
            for (i in 0 until 8) result = (result shl 8) or (buf[pos + i].toLong() and 0xFF)
            pos += 8
            return result
        }
    }
}

/** The fields recovered from a §11.3 inner payload after a successful parse + signature verify. */
internal class ParsedChatDescriptor(
    val chatId: ByteArray,
    val kind: ChatKind,
    val access: ChatAccess,
    val title: String,
    val signingPublic: ByteArray,
    val versionCounter: Long,
)

/** Typed parse outcome — the chat-directory analogue of §10.3's `DirectoryParseResult` (never thrown). */
internal sealed interface ChatParseResult {
    data class Parsed(val descriptor: ParsedChatDescriptor) : ChatParseResult

    data class Rejected(val reason: ChatRejectReason) : ChatParseResult
}

/** Why a §11.3 inner payload was rejected (diagnostics; all map to "drop the entry"). */
internal enum class ChatRejectReason {
    /** Truncated, too short, wrong width, over-cap length prefix, or trailing bytes before the signature. */
    MALFORMED,

    /** An unknown `chat-entry-version` — reject, don't guess (§11.3 / §7). */
    UNKNOWN_VERSION,

    /** A `dm`-kind (or any non-group) entry — the content-level privacy gate; DMs are never surfaced (§11.5). */
    DM_OR_INVALID_KIND,

    /** An `access` byte outside {public, private} — reject-don't-guess (§11.3). */
    INVALID_ACCESS,

    /** The §11.3 Ed25519 signature did not verify against the carried signing-pubkey (libsodium -1). */
    BAD_SIGNATURE,
}
