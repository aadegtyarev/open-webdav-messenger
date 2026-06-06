package org.openwebdav.messenger.directory

/**
 * Community-root-relative paths + the content-addressed entry-name minting for the §10 community user
 * directory (`docs/protocol/webdav-layout.md`). Pure, stateless, deterministic — the same kind of
 * path/name minter as `protocol/ChatPaths` (§1) and `protocol/ChangeEntry` (§9.2), lifted one scope
 * level up (community scope, not chat scope).
 *
 * The minting itself lives in the shared [CollectionPaths] core (byte-identical to the §11 chat
 * directory — same `HashTag` primitive, same 32-char Base32-lower alphabet, same SC16 gate); this object
 * is the §10-named public face, pinning the `directory/` collection segment.
 *
 * The entry name is content-addressed (§10.4) but is **NOT** a §2 message-id (no `order-token "~"
 * content-hash` shape — a directory entry has no §4 order-token): it is the bare 32-char Base32-lower
 * SHA-256 of the entry file bytes, which is filename-safe by construction (alphabet `[a-z2-7]`, no
 * `/`, no `..`, no spaces — SC16-safe; §10.4 / §0).
 */
internal object DirectoryPaths {
    private val paths = CollectionPaths(collection = "directory")

    /** §10.1: the reserved `directory/` collection under the community-root. */
    const val DIRECTORY = "directory"

    /** §10.4: entry-name content-hash length in Base32 characters. */
    const val ENTRY_NAME_LEN = CollectionPaths.ENTRY_NAME_LEN

    /**
     * §10.4: the content-addressed entry file name = `b32lower(SHA-256(entry-file-bytes))[0:32]`.
     * Computed over the EXACT file bytes (the whole §10.2 envelope: header ‖ nonce ‖ ciphertext+tag),
     * so identical bytes → identical name (idempotent re-publish) and any byte flip → a different name
     * (detected by the §3 / §10.6 on-read content-hash check).
     */
    fun entryName(entryFileBytes: ByteArray): String = paths.entryName(entryFileBytes)

    /** §10.4: the `directory/<entry-name>` path for a content-addressed entry. */
    fun entryPath(entryName: String): String = paths.entryPath(entryName)

    /**
     * §10.4/§10.6: `true` iff [name] is a well-formed entry name — exactly [ENTRY_NAME_LEN] characters
     * over the `[a-z2-7]` alphabet. A foreign / malformed name in the shared `directory/` collection
     * is dropped before it is dereferenced (SC16 path-safety + reject-don't-guess), never trusted.
     */
    fun isWellFormedEntryName(name: String): Boolean = paths.isWellFormedEntryName(name)
}
