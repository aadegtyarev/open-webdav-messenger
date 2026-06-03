package org.openwebdav.messenger.message

/**
 * The result of [MessageParser.parse]: either a fully-validated, signature-verified [Message] or a
 * typed [Rejected] (`docs/protocol/webdav-layout.md` §8.1 reject-don't-guess). The parser NEVER throws
 * into the caller and NEVER returns a partially-parsed message — a malformed buffer, an unknown
 * version/kind, an overrunning length prefix, a trailing-byte mismatch, an out-of-range enum, or a
 * signature that does not verify all collapse to [Rejected].
 */
sealed interface ParseResult {
    /** The plaintext deserialized to [message] AND its §8.3 Ed25519 signature verified against the
     * embedded `sender-id-pubkey`. The only result a reader may surface as a real message. */
    data class Parsed(val message: Message) : ParseResult

    /**
     * The plaintext was not a valid, verifiable message. [reason] is a coarse machine-readable cause
     * (for diagnostics/metrics only — every value is "drop the message"); it is deliberately NOT a
     * detailed error path that a caller branches on, so a sender cannot probe which check failed.
     */
    data class Rejected(val reason: RejectReason) : ParseResult
}

/**
 * Why a plaintext was rejected (§8.1). All reasons mean the same thing operationally — the message is
 * dropped. Kept distinct only so a test/diagnostic can assert the specific guard that fired.
 */
enum class RejectReason {
    /** Buffer shorter than the §8.2 minimum (prefix + signature = 100 bytes), or a truncated field. */
    MALFORMED,

    /** `msg-format-version` (§8.2 byte 0) is not the version this build implements. */
    UNKNOWN_VERSION,

    /** `kind` (§8.2 byte 1) is not `text`/`reaction` (a reserved or future kind). */
    UNKNOWN_KIND,

    /**
     * A TLV tag not in the known kind's closed field set, a duplicate tag, a missing required tag, or a
     * `reply-to` / `target-id` value that is not a well-formed §2 file-name reference (§8.4/§8.5).
     */
    BAD_FIELDS,

    /** An enumerated value out of its fixed range (e.g. reaction-index ∉ 0..4, §8.5). */
    OUT_OF_RANGE,

    /** §8.3: the Ed25519 signature did not verify against the embedded sender-id-pubkey (hard reject). */
    BAD_SIGNATURE,
}
