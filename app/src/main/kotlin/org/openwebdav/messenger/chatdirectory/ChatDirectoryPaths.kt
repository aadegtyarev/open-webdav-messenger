package org.openwebdav.messenger.chatdirectory

import org.openwebdav.messenger.protocol.HashTag

/**
 * Community-root-relative paths + the content-addressed entry-name minting for the §11 community chat
 * directory (`docs/protocol/webdav-layout.md`). Pure, stateless, deterministic — byte-identical name
 * minting to the §10 `DirectoryPaths` (same `HashTag` primitive, same 32-char Base32-lower alphabet,
 * same SC16 gate); only the collection segment differs.
 *
 * The entry name is content-addressed (§11.4) but is **NOT** a §2 message-id: it is the bare 32-char
 * Base32-lower SHA-256 of the entry file bytes, which is filename-safe by construction (alphabet
 * `[a-z2-7]`, no `/`, no `..`, no spaces — SC16-safe; §11.4 / §0). The opaque chat-id lives INSIDE the
 * AEAD seal and is NOT a path segment — SC16 gates this file name, not the chat-id value.
 */
internal object ChatDirectoryPaths {
    /**
     * §11.1: the reserved `chat-directory/` collection under the community-root — a distinct sibling of
     * the §10 `directory/` and the community-level `meta/`, and distinct from any chat-root segment
     * (the §10.1 collision rule extended to §11). A chat-directory read/write touches only this
     * collection.
     */
    const val CHAT_DIRECTORY = "chat-directory"

    /** §11.4: entry-name content-hash length in Base32 characters (same width as §10.4). */
    const val ENTRY_NAME_LEN = 32

    /** §11.4 entry-name alphabet (RFC 4648 Base32 lowercase, no padding) — filename-safe (§0/SC16). */
    private val ENTRY_NAME_CHARS = ('a'..'z').toSet() + ('2'..'7').toSet()

    /**
     * §11.4: the content-addressed entry file name = `b32lower(SHA-256(entry-file-bytes))[0:32]`.
     * Computed over the EXACT file bytes (the whole §11.2 envelope: header ‖ nonce ‖ ciphertext+tag),
     * so identical bytes → identical name (idempotent re-publish) and any byte flip → a different name
     * (detected by the §3 / §11.6 on-read content-hash check).
     */
    fun entryName(entryFileBytes: ByteArray): String = HashTag.tag(entryFileBytes, ENTRY_NAME_LEN)

    /** §11.4: the `chat-directory/<entry-name>` path for a content-addressed entry. */
    fun entryPath(entryName: String): String = "$CHAT_DIRECTORY/$entryName"

    /**
     * §11.4/§11.6: `true` iff [name] is a well-formed entry name — exactly [ENTRY_NAME_LEN] characters
     * over the `[a-z2-7]` alphabet. A foreign / malformed name in the shared `chat-directory/`
     * collection is dropped before it is dereferenced (SC16 path-safety + reject-don't-guess), never
     * trusted.
     */
    fun isWellFormedEntryName(name: String): Boolean = name.length == ENTRY_NAME_LEN && name.all { it in ENTRY_NAME_CHARS }
}
