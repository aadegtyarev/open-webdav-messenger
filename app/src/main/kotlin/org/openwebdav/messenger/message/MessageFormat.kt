package org.openwebdav.messenger.message

/**
 * The fixed byte-format constants of the §8 message plaintext (`docs/protocol/webdav-layout.md`).
 * Single source of truth shared by [MessageSerializer] (writer) and [MessageParser] (reader) so the
 * two never drift. All multi-byte integers in the format are big-endian (§8.1 / §0).
 */
internal object MessageFormat {
    /** §8.2 byte 0: the only `msg-format-version` this build implements. Unknown version = reject (§8.1). */
    const val FORMAT_VERSION: Byte = 0x01

    /** §8.2 byte 1: `kind = text`. */
    const val KIND_TEXT: Byte = 0x01

    /** §8.2 byte 1: `kind = reaction`. */
    const val KIND_REACTION: Byte = 0x02

    /** §8.2: `sender-id-pubkey` is the 32-byte Ed25519 public key in the fixed prefix. */
    const val SENDER_PUBKEY_BYTES = 32

    /** §8.2/§8.3: the trailing Ed25519 detached signature (`crypto_sign_BYTES`). */
    const val SIGNATURE_BYTES = 64

    /** §8.2 fixed-prefix size: version(1) + kind(1) + sender-id-pubkey(32) + field-count(2). */
    const val PREFIX_BYTES = 1 + 1 + SENDER_PUBKEY_BYTES + 2

    /**
     * §8.2: the minimum valid plaintext = fixed prefix (36) + zero TLV fields + signature (64) = 100.
     * A plaintext shorter than this cannot be valid → reject (not a bounds error).
     */
    const val MIN_PLAINTEXT_BYTES = PREFIX_BYTES + SIGNATURE_BYTES

    /** §8.2.1: each TLV triple is `tag(1) ‖ length(2 big-endian) ‖ value`. */
    const val TLV_HEADER_BYTES = 3

    /** §8.2.1: the max value length a uint16 length prefix can express. */
    const val MAX_TLV_VALUE_LEN = 0xFFFF

    // ---- Per-kind field tags (§8.4 text / §8.5 reaction) --------------------------------------
    // Tags are interpreted in the context of the `kind` byte (§8.2.1): the same tag number means a
    // different field under a different kind. An unknown tag within a known kind+version = reject.
    // No kind carries a self message-id (§8.6, corrected §8): a message's identity is its §2 file
    // name, assigned at seal, never duplicated inside the plaintext.

    /** §8.4 tag 0x01 / §8.5 tag 0x01: `chat-id` (required, both kinds). */
    const val TAG_CHAT_ID: Byte = 0x01

    /** §8.4 tag 0x02: `reply-to` (optional, text only) — a full §2 file name of the quoted message. */
    const val TAG_REPLY_TO: Byte = 0x02

    /** §8.4 tag 0x03: `body` (required, text only). */
    const val TAG_BODY: Byte = 0x03

    /** §8.4 tag 0x04: `send-timestamp` (required, text only) — uint64 big-endian. */
    const val TAG_SEND_TIMESTAMP: Byte = 0x04

    /** §8.4 tag 0x04: `send-timestamp` value width — an 8-byte big-endian uint64 ([BigEndian.UINT64_BYTES]). */
    const val SEND_TIMESTAMP_BYTES = BigEndian.UINT64_BYTES

    /** §8.5 tag 0x02: `target-id` (required, reaction only) — a full §2 file name of the reacted-to message. */
    const val TAG_TARGET_ID: Byte = 0x02

    /** §8.5 tag 0x03: `reaction-index` (required, reaction only) — a single byte in 0..4. */
    const val TAG_REACTION_INDEX: Byte = 0x03

    /** §8.5 tag 0x04: `reaction-index` value width — exactly 1 byte. */
    const val REACTION_INDEX_BYTES = 1
}
