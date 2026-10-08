# v0.24.1 private membership protocol

> Status: **Approved** — 2026-10-08

## Goal

For release v0.24.1 (code 59), replace the false community-wide roster for private
chats with a verified per-chat roster. A private peer is a participant only after
a valid remote join claim is listed, read, and verified; self remains locally
present for UX and send normalization. General/public retain the exact full
community directory, and DMs retain exactly self plus peer.

This changes membership evidence only. The existing bearer invite remains a
capability to the chat key; its access semantics are unchanged. It introduces no
new trust authority: claims are gated by current verified community directory
entries. After implementation and release, pause for device validation before a
separate removal/revocation feature.

## Affected contracts

- [Chat surface](../surfaces/chat-surface.md)
- [Background delivery](../surfaces/background-delivery.md)
- [Account recovery](../surfaces/account-recovery.md)
- [Architecture](../../architecture.md)
- [WebDAV protocol layout](../../protocol/webdav-layout.md)

## Protocol

Each private chat has a remote membership collection. When creating, opening, or
importing a private chat, a client publishes an idempotent self join claim. The
canonical claim binds protocol version, community ID, chat ID, signing public
identity, and box public identity. It proves control of the signing identity with
Ed25519 and possession of the chat key with an AEAD/MAC proof using the chat key
and explicit domain separation. Verification strictly bounds and validates
canonical bytes, paths, content, community/chat context, signature, and key
possession; it cross-checks the exact signing+box pair against a current verified
community `DirectoryEntry`. Unknown, mismatched, tampered, cross-context/replayed,
and duplicate-identity claims fail closed.

Only remotely listed/read and verified claims may supply private recipients.
Invite creation or an attempt to publish a claim never adds a peer. Local self is
always present, but publication status is truthful. Import stores the invite key,
registers the chat, and attempts self publication. Offline/write failure leaves
retryable pending state and explains that other members may not see the user yet.
Neither publication nor listing implies remote acknowledgement or atomic invite.

Private open reads only its membership collection, never the full directory as a
roster. Its exact community+chat claim snapshot has explicit provenance and is
encrypted, bounded, and corruption-safe; reuse of an existing roster cache is
allowed only if claims resolve to verified entries and kind/provenance cannot
collide. A valid cache gives immediate Ready with silent refresh; a miss is
Loading; listing failure is Unavailable with Retry. Exact context and generation
fence results and invalidation on account/restore/key replacement/kind/chat.

Send and notification paths consume the same atomic verified private Ready
roster. New sends are disabled before remote claims load except when a valid
cache provides Ready. Existing durable envelope retries remain exact. Private
chats must not emit unverified community-wide metadata notifications. General/
public behavior is unchanged; DM behavior is unchanged.

## Lifecycle, privacy, and limits

Legacy private chats do not infer invitees. Each upgraded client publishes its own
claim on open and via background retry; the roster converges as members open. The
UI explains this transitional, append-only membership state. Removal, revocation,
roles/host authority, and deleting history already downloaded are non-goals for
this release and require a separate approved feature.

Backup/restore does not export remote claims or cache as authoritative membership.
A restored identity/key republishes its own claim and invalidates cache. These are
best-effort operations with honest status, not remote-acknowledged or crash-atomic
membership. Collection listing/read and claim sizes/counts are bounded with DoS
limits. Never reveal raw chat keys, private keys, or credentials. The WebDAV disk
operator may observe membership-collection traffic/existence consistent with
current chat metadata; this is an explicit privacy limitation.

## Acceptance and validation

- Codec/crypto, canonicality, exact-context, adversarial, safe-path, bounds, and
  resource-limit tests cover valid and rejected claims (including duplicate,
  replay/cross-context, unknown, mismatch, and tampering).
- Tests cover create/import, offline/write failure and pending retry, reopen,
  legacy convergence without invitee inference, valid cache, cache miss/corruption,
  listing failure/retry, and cache invalidation.
- Tests prove private recipient exactness and no community-wide bleed; General,
  public, and DM behavior remains unchanged. Exercise stale account/chat/key/restore
  races, send/notification snapshot consistency, and backup exclusion/republication.
- UI tests/checks cover truthful pending/Ready/Unavailable status, retry, and
  accessibility. Run full project gates, including Gradle tests, ktlint, and
  Android lint. Luna implements; Sol reviews. Device validation is a separate
  release gate after v0.24.1 and before any removal/revocation feature.

## Scope boundary

Do not change bearer-invite key access semantics, introduce a trust authority,
claim acknowledgement/atomicity, or broaden General/public/DM membership behavior.
If implementation requires any of these, stop for approval. Protocol and
architecture documentation changes belong to this feature only if they specify
this approved protocol; implementation and release/device validation are outside
this contract-recording task.
