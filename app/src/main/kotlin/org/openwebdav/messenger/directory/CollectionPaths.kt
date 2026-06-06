package org.openwebdav.messenger.directory

import org.openwebdav.messenger.protocol.HashTag

/**
 * Shared content-addressed path/name minting for a community-scoped directory collection
 * (`docs/protocol/webdav-layout.md` §10.4 / §11.4). The §10 user directory and the §11 chat directory
 * mint entry names **byte-identically** — `b32lower(SHA-256(entry-file-bytes))[0:32]` over the exact
 * envelope bytes, the same [HashTag.BASE32_LOWER_CHARS] filename-safe alphabet, the same SC16 gate —
 * and differ ONLY in the reserved collection segment. This is that one minter, parameterised by
 * [collection]; `DirectoryPaths` (§10.1 `directory/`) and `ChatDirectoryPaths` (§11.1 `chat-directory/`)
 * hold one instance each and expose it under their own §-numbered public members (behaviour identical to
 * the two former private copies).
 *
 * The entry name is content-addressed but is **NOT** a §2 message-id (no `order-token "~" content-hash`
 * shape): it is the bare 32-char Base32-lower SHA-256, filename-safe by construction (no `/`, no `..`,
 * no spaces — SC16-safe; §0).
 */
internal class CollectionPaths(
    /** The reserved collection segment under the community-root (`directory` / `chat-directory`). */
    val collection: String,
) {
    /**
     * The content-addressed entry file name = `b32lower(SHA-256(entry-file-bytes))[0:ENTRY_NAME_LEN]`,
     * computed over the EXACT file bytes (the whole envelope: header ‖ nonce ‖ ciphertext+tag), so
     * identical bytes → identical name (idempotent re-publish) and any byte flip → a different name
     * (detected by the on-read content-hash check, §3 / §10.6 / §11.6).
     */
    fun entryName(entryFileBytes: ByteArray): String = HashTag.tag(entryFileBytes, ENTRY_NAME_LEN)

    /** The `<collection>/<entry-name>` path for a content-addressed entry. */
    fun entryPath(entryName: String): String = "$collection/$entryName"

    /**
     * `true` iff [name] is a well-formed entry name — exactly [ENTRY_NAME_LEN] characters over the
     * `[a-z2-7]` Base32-lower alphabet. A foreign / malformed name in the shared collection is dropped
     * before it is dereferenced (SC16 path-safety + reject-don't-guess), never trusted.
     */
    fun isWellFormedEntryName(name: String): Boolean = name.length == ENTRY_NAME_LEN && name.all { it in HashTag.BASE32_LOWER_CHARS }

    companion object {
        /** §10.4 / §11.4: entry-name content-hash length in Base32 characters. */
        const val ENTRY_NAME_LEN = 32
    }
}
