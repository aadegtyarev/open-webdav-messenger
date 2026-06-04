package org.openwebdav.messenger.directory

/**
 * The result of [DirectoryService.publishEntry] (§10.4 — `docs/protocol/webdav-layout.md`). The publish
 * is idempotent on identical content (same bytes → same content-addressed name → idempotent `PUT`); a
 * changed entry is a new superseding version (§10.5).
 *
 * Like the `sync` `*Outcome`/`*Result` and the `crypto` `OpenResult`, the directory outcomes are
 * **typed — never thrown**: a publish or read never raises to the caller.
 */
sealed interface PublishOutcome {
    /**
     * The entry was written (or was already present — an idempotent re-publish of identical bytes).
     * [entryName] is the §10.4 content-addressed file name the entry landed at.
     */
    data class Published(val entryName: String) : PublishOutcome

    /**
     * The write could not complete this attempt (transport back-off: 429 / timeout / transport error,
     * or a fail-closed path/cleartext rejection). Re-running the publish completes it (append-only,
     * idempotent). [reason] is a short diagnostic, never surfaced as a crash.
     */
    data class Failed(val reason: String) : PublishOutcome
}

/**
 * The result of [DirectoryService.readDirectory] (§10.6). Carries the **verified** entries resolved to
 * the latest version per signing-pubkey, plus a count of rejected entries (diagnostics only — never
 * surfaced as members). A read never throws: a listing failure yields [entries] empty + [listingFailed].
 *
 * @property entries the verified [DirectoryEntry] set, latest per signing-pubkey (§10.5). In-memory,
 *   recomputed per read from the on-disk source of truth (no local cache this feature — §10.6).
 * @property rejectedCount how many entry files were dropped (wrong/absent community key, bad signature,
 *   malformed/truncated, content-hash mismatch, oversize) — for diagnostics, not members (§10.6).
 * @property listingFailed true when the `PROPFIND Depth: 1` listing itself failed (429/timeout/error);
 *   the read returns whatever it could (typically empty) without crashing — retry next cycle.
 */
data class DirectoryReadResult(
    val entries: List<DirectoryEntry>,
    val rejectedCount: Int,
    val listingFailed: Boolean = false,
)
