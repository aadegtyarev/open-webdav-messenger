package org.openwebdav.messenger.chatdirectory

import org.openwebdav.messenger.identity.PublicIdentity

/**
 * The fixed byte-format constants of the §11.3 chat-directory inner signed payload
 * (`docs/protocol/webdav-layout.md`). Single source of truth shared by [ChatDescriptorCodec]'s
 * serialize (writer) and parse (reader) paths so the two never drift. All multi-byte integers are
 * big-endian (§11.3 / §0). Mirrors the §10.3 `DirectoryFormat` idiom over a different field set.
 *
 * Inner payload layout (§11.3):
 * ```
 * chat-entry-version(0x01) ‖ signing-pubkey(32) ‖ version-counter(uint64 BE) ‖ kind(1) ‖ access(1)
 *   ‖ chat-id-len(uint16 BE) ‖ chat-id(C) ‖ title-len(uint16 BE) ‖ title(T) ‖ signature(64)
 * ```
 * The signed range is everything before the trailing 64-byte signature (§11.3). The `signing-pubkey`
 * is inside that range, so the claimed signer key is itself signed (the §8.3/§10.3 property): it cannot
 * be swapped without breaking the signature. It attributes *who authored this version*, NOT
 * chat-ownership authority (§11.5 / threat (a)).
 */
internal object ChatDescriptorFormat {
    /** §11.3 byte 0: the only `chat-entry-version` this build implements. Unknown version = reject (§11.3). */
    const val ENTRY_VERSION: Byte = 0x01

    /** §11.3: the author's Ed25519 signing public key width (`crypto_sign_PUBLICKEYBYTES`). */
    const val SIGNING_PUBKEY_BYTES = PublicIdentity.SIGN_PUB_BYTES

    /** §11.3: the signed monotonic per-chat-id `version-counter` width — a big-endian uint64. */
    const val VERSION_COUNTER_BYTES = 8

    /** §11.3: the closed single-byte `kind` enum width (§8.5 reaction-index idiom — closed-range byte). */
    const val KIND_BYTES = 1

    /** §11.3: the closed single-byte `access` enum width. */
    const val ACCESS_BYTES = 1

    /** §11.3: the `chat-id-len` prefix width — a big-endian uint16. */
    const val CHAT_ID_LEN_BYTES = 2

    /** §11.3: the `title-len` prefix width — a big-endian uint16. */
    const val TITLE_LEN_BYTES = 2

    /** §11.3/§8.3: the trailing Ed25519 detached signature (`crypto_sign_BYTES`). */
    const val SIGNATURE_BYTES = 64

    /** §11.3 `kind` byte: a group chat — the ONLY valid kind in the chat directory. */
    const val KIND_GROUP: Int = 0x01

    /**
     * §11.3 `kind` byte: a 1:1 direct message. Encoded so the rejection is explicit, but it is a HARD
     * REJECT at publish AND on read (the content-level privacy gate — DMs are never discoverable, the
     * PM scope decision 2026-06-04 / §11.5). It is NOT a valid chat-directory kind.
     */
    const val KIND_DM: Int = 0x00

    /**
     * §11.3 `access` byte: a public chat — readable by every onboarded community member under the
     * community key, sealed from the disk operator (public *within the community*, not world-readable;
     * architecture decision 9 revision / SC2, 2026-06-06).
     */
    const val ACCESS_PUBLIC: Int = 0x00

    /** §11.3 `access` byte: a private chat (content key out-of-band; never in the directory). */
    const val ACCESS_PRIVATE: Int = 0x01

    /**
     * §11.3 fixed prefix preceding the variable-length chat-id:
     * version(1) + signing-pubkey(32) + version-counter(8) + kind(1) + access(1) + chat-id-len(2) = 45.
     */
    const val PREFIX_BEFORE_CHAT_ID_BYTES =
        1 + SIGNING_PUBKEY_BYTES + VERSION_COUNTER_BYTES + KIND_BYTES + ACCESS_BYTES + CHAT_ID_LEN_BYTES

    /**
     * §11.3: the minimum valid inner payload = fixed prefix before chat-id (45) + empty chat-id +
     * title-len(2) + empty title + signature(64) = 111. A payload shorter than this cannot be valid →
     * reject (not a bounds error), mirroring the §10.3 `MIN_PAYLOAD_BYTES` pattern.
     */
    const val MIN_PAYLOAD_BYTES = PREFIX_BEFORE_CHAT_ID_BYTES + TITLE_LEN_BYTES + SIGNATURE_BYTES

    /**
     * §11.3 chat-id cap: the maximum byte length of the opaque chat-id. The chat-id grammar is still
     * `[?]` in §8 (pinned by a later feature); it is carried opaque/length-prefixed here, bounded so a
     * crafted length prefix cannot allocate unbounded. 256 bytes is generous and well under the uint16
     * max. A length prefix above this (or one that overruns the buffer) is a reject (§11.3).
     */
    const val MAX_CHAT_ID_BYTES = 256

    /**
     * §11.3 title cap: the maximum UTF-8 byte length of a title — mirrors the §10.3 display-name cap
     * (256 bytes, the plan's explicit anchor). A length prefix above this (or one that overruns the
     * buffer) is a reject (§11.3 reject-don't-guess).
     */
    const val MAX_TITLE_BYTES = 256

    init {
        // The §11.3 key width and PublicIdentity's width are two names for the same 32-byte key; this
        // build-time guard makes a future drift fail loudly here instead of leaving a silent length
        // mismatch on the parse path (mirrors DirectoryFormat's companion init guard).
        require(SIGNING_PUBKEY_BYTES == PublicIdentity.SIGN_PUB_BYTES) {
            "SIGNING_PUBKEY_BYTES must equal PublicIdentity.SIGN_PUB_BYTES"
        }
    }
}
