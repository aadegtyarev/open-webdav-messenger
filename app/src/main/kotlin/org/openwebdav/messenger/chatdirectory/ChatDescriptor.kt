package org.openwebdav.messenger.chatdirectory

// The chat taxonomy axes the chat directory encodes (`docs/protocol/webdav-layout.md` §11.3; the
// architecture chat taxonomy). Closed enums — a value outside them is a typed rejection on read
// (reject-don't-guess), never a guessed default.

/**
 * §11.3 `kind`: the chat taxonomy's first axis. In the chat directory **only [GROUP] is valid**;
 * [DM] is a HARD REJECT at publish and on read — DMs are never discoverable (the social-graph privacy
 * gate, PM scope decision 2026-06-04). [DM] exists in this enum only so the publish API can name the
 * rejected case explicitly; it never reaches a verified [ChatDirectoryEntry].
 */
enum class ChatKind {
    /** A 1:1 direct message — NEVER published, NEVER surfaced (hard reject, §11.5). */
    DM,

    /** A group chat — the only discoverable kind. */
    GROUP,
}

/**
 * §11.3 `access`: the chat taxonomy's second axis. Both values are valid for a [ChatKind.GROUP] entry;
 * any byte outside this enum is a typed rejection on read.
 */
enum class ChatAccess {
    /**
     * A public chat — readable by every onboarded community member under the community key, sealed from
     * the disk operator. "Public" means public *within the community*, not private to specific members;
     * it is NOT world-readable (architecture decision 9 revision / SC2, 2026-06-06).
     */
    PUBLIC,

    /** A private chat — its content key is out-of-band and NEVER in the directory (existence + title only). */
    PRIVATE,
}

/**
 * A **verified** chat-directory record (`docs/protocol/webdav-layout.md` §11.3), returned by
 * `ChatDirectoryService` only after the entry's AEAD-open with the community key succeeded AND its
 * Ed25519 signature verified against the carried [publishedBySigningKey].
 *
 * - [chatId] is carried **opaque + length-prefixed** (its grammar is still `[?]` in §8, pinned by a
 *   later feature) — it lives inside the AEAD seal, never in a file name, so it is NOT subject to the
 *   §0/SC16 filename grammar (that gates the entry FILE name, not the chat-id value).
 * - [kind] is always [ChatKind.GROUP] here ([ChatKind.DM] is dropped on read — §11.5).
 * - [publishedBySigningKey] is the 32-byte Ed25519 key that signed this descriptor version — **who
 *   authored it, NOT chat-ownership authority** (under flat trust any member can publish a competing
 *   descriptor for a chat-id; the latest signed version wins — §11.5 / threat (a)).
 *
 * @property chatId the opaque chat identifier bytes, as published (no normalization — a later feature
 *   pins its grammar).
 * @property kind the chat kind — always [ChatKind.GROUP] for a surfaced entry.
 * @property access [ChatAccess.PUBLIC] or [ChatAccess.PRIVATE]; private surfaces existence + title only.
 * @property title raw UTF-8 title as published (no rendering / normalization — UI concern).
 * @property publishedBySigningKey the 32-byte Ed25519 signing key that authored this version.
 */
class ChatDirectoryEntry(
    chatId: ByteArray,
    val kind: ChatKind,
    val access: ChatAccess,
    val title: String,
    publishedBySigningKey: ByteArray,
) {
    val chatId: ByteArray = chatId.copyOf()
    val publishedBySigningKey: ByteArray = publishedBySigningKey.copyOf()

    init {
        require(this.publishedBySigningKey.size == ChatDescriptorFormat.SIGNING_PUBKEY_BYTES) {
            "publishedBySigningKey must be ${ChatDescriptorFormat.SIGNING_PUBKEY_BYTES} bytes"
        }
        require(this.chatId.size <= ChatDescriptorFormat.MAX_CHAT_ID_BYTES) {
            "chatId exceeds ${ChatDescriptorFormat.MAX_CHAT_ID_BYTES} bytes"
        }
    }

    /** A fresh copy of the opaque chat-id bytes. */
    fun copyChatId(): ByteArray = chatId.copyOf()

    /** A fresh copy of the author's 32-byte signing public key (public bytes — safe to hand out). */
    fun copyPublishedBySigningKey(): ByteArray = publishedBySigningKey.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ChatDirectoryEntry &&
            chatId.contentEquals(other.chatId) &&
            kind == other.kind &&
            access == other.access &&
            title == other.title &&
            publishedBySigningKey.contentEquals(other.publishedBySigningKey)

    override fun hashCode(): Int {
        var result = chatId.contentHashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + access.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + publishedBySigningKey.contentHashCode()
        return result
    }

    override fun toString(): String = "ChatDirectoryEntry(kind=$kind, access=$access, title=$title, chatId=***, key=***)"
}
