# v0.24.1 private membership protocol

> Status: **Implementation complete; final review and device validation pending** — 2026-10-08

## Goal

For release v0.24.1 (code 59), replace the false community-wide roster for private
chats with a verified per-chat roster. A private peer is a participant only after
a valid remote join claim is listed, read, and verified; self remains locally
present for UX and send normalization. General/public retain the exact full
community directory, and DMs retain exactly self plus peer.

Membership evidence only changes; invite still grants only chatId+chatKey
capability, with no community ID/key/credentials or added privilege. New invites
require identity-authenticated strict `access=public|private` metadata, selecting
roster protocol only. Legacy missing/invalid/tampered access/signature is rejected
before mutation with localized actionable fresh-invite guidance; never guess. A claim
proves key possession and self-asserted identity; accessible directory matching
strengthens provenance, not required for joining the chat. Pause for device
validation before separate removal/revocation work.

## Affected contracts

- [Chat surface](../surfaces/chat-surface.md)
- [Background delivery](../surfaces/background-delivery.md)
- [Account recovery](../surfaces/account-recovery.md)
- [Architecture](../../architecture.md)
- [WebDAV protocol layout](../../protocol/webdav-layout.md)

## Protocol

Each private chat has a remote membership collection. Claims bind domain/version,
chat ID, signing+box identities, and canonical bytes. Optional display names are
trimmed consistently on signing and parse; blank names remain valid and render via
existing unavailable-name UI. Wire trust is chatId+chatKey; local communityId,
URL, and username are excluded. Ed25519 proves signer control; chat-key AEAD proves
possession with canonical domain+wire-version+length-prefixed chatId AAD (message
envelopes are unchanged). Bind path/filename/content and reject bounds, path,
context, signature, or key-proof failures. An exact accessible
directory pair strengthens provenance; absence permits chat-only membership.
Conflicting directory identities, equivocation, tampering, and cross-chat claims fail closed.

Only remotely listed/read verified claims supply private recipients; invite or
publication attempts never infer a peer. Import stores the key, registers the chat,
and publishes self with truthful pending/uploaded status. A keyed account/community/
chat mutex serializes pending creation, PUT, and local commit. Stable-account checks
fence before remote start and after PUT; store replacement, generation commit, and local
runtime install share one gate for onboarding/restore; roster/claim WebDAV starts after
release. Rollback is non-cancellable. Pending and fresh queues have independent persisted
cursors; a repeatedly
failing fresh claim cannot reset pending progress. Each queue is bounded per cycle and
uploaded claims are skipped. No remote acknowledgement or atomic invite is implied.
Private-only names stay labelled.

Private open reads only its membership collection. Cache provenance binds local
community+chat, key, identity, kind, and protocol; it is encrypted, bounded, and
account-local. Cache gives immediate Ready; misses load; listing failures allow Retry.
Exact context/generation fences results; replacement, restore, kind, and key changes invalidate it.

Send and notification paths consume the same atomic verified private Ready
roster. New sends are disabled before remote claims load except when a valid
cache provides Ready. Existing durable envelope retries remain exact. Private
chats must not emit unverified community-wide metadata notifications. General/
public behavior is unchanged; DM behavior is unchanged.

## Lifecycle, privacy, and limits

Registry/backup stores `public`/`private`; missing access is `unknown`. Only an
exact `kind=general` row matching both durable community and stored-config anchors
may migrate unknown to public. Other groups require a verified descriptor; unknown
fails closed. Legacy private chats infer no invitees; open and background retry
publish self. Removal, revocation, roles, and history erasure are separate work.

Outer backup payload and binary codec are v3; strict v2 decode preserves explicit
access, while v1 access remains unknown. Claims/cache are not exported. Restore
invalidates cache/pending work and republishes known private self claims best-effort;
no acknowledgement or crash atomicity. Bound reads, hide keys/credentials, and grant
no directory or community authority.

## Acceptance and validation

- Codec/crypto, canonicality, exact-context, adversarial, safe-path, bounds, and
  resource-limit tests cover valid/rejected claims, duplicate/equivocation,
  replay/cross-chat, unknown, mismatch, and tampering.
- Tests cover create/import, offline/write failure and pending retry, reopen,
  legacy convergence without invitee inference, valid cache, cache miss/corruption,
  listing failure/retry, and cache invalidation.
- Tests prove unchanged General/public/DM, exact-anchor migration, Base32 matching,
  and no stale descriptor commits. Cover concurrent and suspended-PUT restore/key-change
  races, >64-chat fairness, and v2/v3 backup roundtrip/restore/rejection.
- UI tests/checks cover truthful pending/Ready/Unavailable status, retry, and
  accessibility. Run full project gates, including Gradle tests, ktlint, and
  Android lint. Luna implements; Sol reviews. Device validation is a separate
  release gate after v0.24.1 and before any removal/revocation feature.

## Scope boundary

Do not change bearer-invite access semantics, add trust authority or claim
acknowledgement/atomicity, or alter General/public/DM behavior. Stop if required.
Protocol/architecture updates may specify only this approved protocol.
