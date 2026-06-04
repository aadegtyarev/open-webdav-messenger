package org.openwebdav.messenger.directory

import org.openwebdav.messenger.identity.PublicIdentity

/**
 * The fixed byte-format constants of the §10.3 directory inner signed payload
 * (`docs/protocol/webdav-layout.md`). Single source of truth shared by [DirectoryEntryCodec]'s
 * serialize (writer) and parse (reader) paths so the two never drift. All multi-byte integers are
 * big-endian (§10.3 / §0).
 *
 * Inner payload layout (§10.3):
 * ```
 * dir-entry-version(0x01) ‖ signing-pubkey(32) ‖ box-pubkey(32) ‖ version-counter(uint64 BE)
 *   ‖ display-name-len(uint16 BE) ‖ display-name(L) ‖ signature(64)
 * ```
 * The signed range is `[0 .. 75+L)` — version through the display-name, EXCLUDING the trailing
 * 64-byte signature (§10.3). The `signing-pubkey` is inside that range, so the claimed signer key
 * is itself signed (the §8.3 property): it cannot be swapped without breaking the signature.
 */
internal object DirectoryFormat {
    /** §10.3 byte 0: the only `dir-entry-version` this build implements. Unknown version = reject (§10.3). */
    const val ENTRY_VERSION: Byte = 0x01

    /** §10.3: the author's Ed25519 signing public key width (`crypto_sign_PUBLICKEYBYTES`). */
    const val SIGNING_PUBKEY_BYTES = PublicIdentity.SIGN_PUB_BYTES

    /** §10.3: the author's X25519 box public key width (`crypto_box_PUBLICKEYBYTES`). */
    const val BOX_PUBKEY_BYTES = PublicIdentity.BOX_PUB_BYTES

    /** §10.3: the signed monotonic per-author `version-counter` width — a big-endian uint64. */
    const val VERSION_COUNTER_BYTES = 8

    /** §10.3: the `display-name-len` prefix width — a big-endian uint16. */
    const val DISPLAY_NAME_LEN_BYTES = 2

    /** §10.3/§8.3: the trailing Ed25519 detached signature (`crypto_sign_BYTES`). */
    const val SIGNATURE_BYTES = 64

    /**
     * §10.3 fixed prefix preceding the variable-length display-name:
     * version(1) + signing-pubkey(32) + box-pubkey(32) + version-counter(8) + display-name-len(2) = 75.
     */
    const val PREFIX_BYTES =
        1 + SIGNING_PUBKEY_BYTES + BOX_PUBKEY_BYTES + VERSION_COUNTER_BYTES + DISPLAY_NAME_LEN_BYTES

    /**
     * §10.3: the minimum valid inner payload = fixed prefix (75) + empty display-name + signature (64)
     * = 139. A payload shorter than this cannot be valid → reject (not a bounds error).
     */
    const val MIN_PAYLOAD_BYTES = PREFIX_BYTES + SIGNATURE_BYTES

    /**
     * §10.3 display-name cap: the maximum UTF-8 byte length of a display-name. A length prefix above
     * this (or one that overruns the buffer) is a reject (§10.3 reject-don't-guess). 256 bytes is
     * generous for a human display-name while bounding the field; it is well under the uint16 max.
     */
    const val MAX_DISPLAY_NAME_BYTES = 256

    init {
        // The §10.3 key widths and PublicIdentity's widths are two names for the same 32-byte keys;
        // this build-time guard makes a future drift fail loudly here instead of leaving a silent
        // length mismatch on the parse path (mirrors MessageParser's companion init guard).
        require(SIGNING_PUBKEY_BYTES == PublicIdentity.SIGN_PUB_BYTES) {
            "SIGNING_PUBKEY_BYTES must equal PublicIdentity.SIGN_PUB_BYTES"
        }
        require(BOX_PUBKEY_BYTES == PublicIdentity.BOX_PUB_BYTES) {
            "BOX_PUBKEY_BYTES must equal PublicIdentity.BOX_PUB_BYTES"
        }
    }
}
