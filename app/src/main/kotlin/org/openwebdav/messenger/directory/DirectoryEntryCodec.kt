package org.openwebdav.messenger.directory

import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.message.BigEndian
import java.io.ByteArrayOutputStream

/**
 * Serializes a directory entry to the §10.3 inner signed-payload bytes and applies / verifies the
 * §10.3 Ed25519 signature (`docs/protocol/webdav-layout.md`). The output is exactly the plaintext fed
 * to / returned by the §10.2 community-key AEAD seal/open (mirrors §8 `MessageSerializer` /
 * `MessageParser`, one idiom across both inner formats).
 *
 * Strictly **reject-don't-guess** (§10.3): every length prefix is validated against the buffer, no
 * `!!` is used, no index/bounds exception escapes — every failure is a typed [DirectoryParseResult].
 * The signature is verified LAST, after the structure is fully valid, over the exact `[0 .. len-64)`
 * signed range against the embedded `signing-pubkey`.
 */
internal class DirectoryEntryCodec(private val identity: IdentityCrypto) {
    /**
     * Build the §10.3 signed payload for the entry (`version ‖ signing-pubkey ‖ box-pubkey ‖
     * version-counter ‖ display-name-len ‖ display-name`), Ed25519-sign that exact range with
     * [signingSecret] (§10.3), and append the 64-byte signature. Returns the complete §10.3 inner
     * payload ready for the §10.2 AEAD seal.
     *
     * @throws IllegalArgumentException if [displayName] exceeds [DirectoryFormat.MAX_DISPLAY_NAME_BYTES]
     *   or [versionCounter] is negative — a programming error at the publish call site (the read path
     *   never throws; an over-cap/negative value arriving FROM disk is a typed parse rejection, below).
     */
    fun signAndSerialize(
        displayName: String,
        signingPublic: ByteArray,
        boxPublic: ByteArray,
        versionCounter: Long,
        signingSecret: ByteArray,
    ): ByteArray {
        require(signingPublic.size == DirectoryFormat.SIGNING_PUBKEY_BYTES) { "signing-pubkey must be 32 bytes" }
        require(boxPublic.size == DirectoryFormat.BOX_PUBKEY_BYTES) { "box-pubkey must be 32 bytes" }
        require(versionCounter >= 0) { "version-counter must be non-negative" }
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= DirectoryFormat.MAX_DISPLAY_NAME_BYTES) {
            "display-name exceeds ${DirectoryFormat.MAX_DISPLAY_NAME_BYTES} UTF-8 bytes"
        }
        val signedPayload = serializeSignedPayload(signingPublic, boxPublic, versionCounter, nameBytes)
        val signature = identity.sign(signedPayload, signingSecret)
        check(signature.size == DirectoryFormat.SIGNATURE_BYTES) {
            "Ed25519 detached signature must be ${DirectoryFormat.SIGNATURE_BYTES} bytes"
        }
        return signedPayload + signature
    }

    /** §10.3: the signed-payload byte range (everything before the trailing 64-byte signature). */
    private fun serializeSignedPayload(
        signingPublic: ByteArray,
        boxPublic: ByteArray,
        versionCounter: Long,
        nameBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(DirectoryFormat.PREFIX_BYTES + nameBytes.size)
        out.write(DirectoryFormat.ENTRY_VERSION.toInt())
        out.write(signingPublic)
        out.write(boxPublic)
        BigEndian.writeUint64Be(out, versionCounter)
        BigEndian.writeUint16Be(out, nameBytes.size)
        out.write(nameBytes)
        return out.toByteArray()
    }

    /**
     * §10.3/§10.6: parse [payload] (the AEAD-opened inner bytes) into a verified [ParsedEntry], or a
     * typed [DirectoryParseResult.Rejected] on any failure (unknown version, malformed/truncated,
     * over-cap display-name, bad signature). Never throws.
     */
    fun parse(payload: ByteArray): DirectoryParseResult {
        if (payload.size < DirectoryFormat.MIN_PAYLOAD_BYTES) {
            return DirectoryParseResult.Rejected(DirectoryRejectReason.MALFORMED)
        }
        val signatureStart = payload.size - DirectoryFormat.SIGNATURE_BYTES
        val cursor = Cursor(payload, signatureStart)

        val version = cursor.u8() ?: return reject(DirectoryRejectReason.MALFORMED)
        if (version.toByte() != DirectoryFormat.ENTRY_VERSION) return reject(DirectoryRejectReason.UNKNOWN_VERSION)
        val signingPub = cursor.take(DirectoryFormat.SIGNING_PUBKEY_BYTES) ?: return reject(DirectoryRejectReason.MALFORMED)
        val boxPub = cursor.take(DirectoryFormat.BOX_PUBKEY_BYTES) ?: return reject(DirectoryRejectReason.MALFORMED)
        val versionCounter = cursor.u64() ?: return reject(DirectoryRejectReason.MALFORMED)
        val nameLen = cursor.u16() ?: return reject(DirectoryRejectReason.MALFORMED)
        if (nameLen > DirectoryFormat.MAX_DISPLAY_NAME_BYTES) return reject(DirectoryRejectReason.MALFORMED)
        val nameBytes = cursor.take(nameLen) ?: return reject(DirectoryRejectReason.MALFORMED)
        // The display-name MUST end exactly at the start of the 64-byte signature: no trailing bytes
        // between the name and the signature (§10.3 closed layout). An under/overrun is a reject.
        if (cursor.pos != signatureStart) return reject(DirectoryRejectReason.MALFORMED)

        // Verify LAST, over the exact signed range, against the embedded signing-pubkey (§10.3 hard
        // reject on libsodium -1). The signing-pubkey is inside the signed range, so it cannot be
        // swapped without breaking the signature.
        val signedPayload = payload.copyOfRange(0, signatureStart)
        val signature = payload.copyOfRange(signatureStart, payload.size)
        if (!identity.verify(signature, signedPayload, signingPub)) {
            return reject(DirectoryRejectReason.BAD_SIGNATURE)
        }
        val displayName = nameBytes.toString(Charsets.UTF_8)
        return DirectoryParseResult.Parsed(ParsedEntry(displayName, signingPub, boxPub, versionCounter))
    }

    private fun reject(reason: DirectoryRejectReason): DirectoryParseResult = DirectoryParseResult.Rejected(reason)

    /**
     * A bounds-checked forward reader over the inner payload, bounded at [limit] (the start of the
     * trailing signature) so a read never crosses into the signature bytes. Every read validates the
     * span against [limit] and returns `null` on overrun instead of throwing (§10.3 reject-don't-guess;
     * stack-notes Kotlin null-safety: no `!!` on parse paths). Big-endian.
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

/** The fields recovered from a §10.3 inner payload after a successful parse + signature verify. */
internal class ParsedEntry(
    val displayName: String,
    val signingPublic: ByteArray,
    val boxPublic: ByteArray,
    val versionCounter: Long,
)

/** Typed parse outcome — the inner-payload analogue of `message`'s `ParseResult` (never thrown). */
internal sealed interface DirectoryParseResult {
    data class Parsed(val entry: ParsedEntry) : DirectoryParseResult

    data class Rejected(val reason: DirectoryRejectReason) : DirectoryParseResult
}

/** Why a §10.3 inner payload was rejected (diagnostics; all map to "drop the entry"). */
internal enum class DirectoryRejectReason {
    /** Truncated, too short, wrong width, or trailing bytes between the name and the signature. */
    MALFORMED,

    /** An unknown `dir-entry-version` — reject, don't guess (§10.3 / §7). */
    UNKNOWN_VERSION,

    /** The §10.3 Ed25519 signature did not verify against the carried signing-pubkey (libsodium -1). */
    BAD_SIGNATURE,
}
