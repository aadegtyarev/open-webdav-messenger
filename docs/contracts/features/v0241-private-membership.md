# v0.24.1 private membership protocol

> Status: **Approved** — 2026-10-08

## Goal

For release v0.24.1 (code 59), replace the false community-wide roster for private
chats with a verified per-chat roster. A private peer is a participant only after
a valid remote join claim is listed, read, and verified; self remains locally
present for UX and send normalization. General/public retain the exact full
community directory, and DMs retain exactly self plus peer.

Membership evidence only changes. The bearer invite remains a capability to the
chat key; it carries no community ID/key/credentials and grants no privilege. A
claim proves chat-key possession and self-asserted identity; an accessible current
directory match strengthens provenance but is not required to join that chat.
Pause for device validation before separate removal/revocation work.

## Affected contracts

- [Chat surface](../surfaces/chat-surface.md)
- [Background delivery](../surfaces/background-delivery.md)
- [Account recovery](../surfaces/account-recovery.md)
- [Architecture](../../architecture.md)
- [WebDAV protocol layout](../../protocol/webdav-layout.md)

## Protocol

Each private chat has a remote membership collection. Create/open/import
publishes an idempotent self claim binding protocol domain/version, chat ID,
signing+box public identities, and canonical bytes. The wire identity is chatId+chatKey; do not put local communityId on wire or
derive trust from URL/username.
Ed25519 proves signer control; chat-key AEAD/MAC with domain separation proves
key possession. Bind canonical collection/path/filename/content; strictly validate
bounds, paths, canonicality, context, signature, and key proof. If the current
community directory is accessible, an exact signing+box match marks community
verification; absence/inaccessibility does not reject a valid claim or grant
community access. A conflicting directory entry for the signer fails closed.
Exact same signer+canonical claim is one member; signer equivocation (different
box key or chat context), tampering, and cross-chat claims fail closed.

Only remotely listed/read and verified claims may supply private recipients.
Invite creation or an attempt to publish a claim never adds a peer. Local self is
always present, but publication status is truthful. Import stores the invite key,
registers the chat, and attempts self publication. Offline/write failure leaves
retryable pending state and explains that other members may not see the user yet.
Neither publication nor listing implies remote acknowledgement or atomic invite.
UI labels self-asserted display names as private-chat-only when no accessible
matching directory entry exists; it never implies community verification.

Private open reads only its membership collection, never the full directory as a
roster. Cache provenance binds local community+chat, chat key, identity, kind, and
protocol; it is encrypted, bounded, corruption-safe, and cannot cross local
accounts. A valid cache gives immediate Ready with silent refresh; a miss is
Loading; listing failure is Unavailable with Retry. Exact context/generation fence
results; account/restore/key replacement/kind/chat changes invalidate it.

Send and notification paths consume the same atomic verified private Ready
roster. New sends are disabled before remote claims load except when a valid
cache provides Ready. Existing durable envelope retries remain exact. Private
chats must not emit unverified community-wide metadata notifications. General/
public behavior is unchanged; DM behavior is unchanged.

## Lifecycle, privacy, and limits

Chat registry/backup stores durable access `public`/`private`; legacy missing
access is `unknown` until a verified ChatDirectory descriptor resolves it. Unknown
fails closed: no private claims, community-wide roster, or send; show retry status.
Legacy private chats infer no invitees; upgraded clients publish self on open and
background retry. Removal, revocation, roles/host authority, and downloaded-history
erasure are separate work.

Backup codec bumps version, keeps v2 decode, and exports access only—not claims or
cache as membership authority. Restore preserves access, invalidates cache, and
republishes self for known private chats. Best effort; no remote acknowledgement
or crash atomicity. Bound lists/reads against DoS. Never reveal raw keys or
credentials. Disk operators may observe collection traffic/existence; private
claims grant neither community membership, directory trust, nor community access.

## Acceptance and validation

- Codec/crypto, canonicality, exact-context, adversarial, safe-path, bounds, and
  resource-limit tests cover valid/rejected claims, duplicate/equivocation,
  replay/cross-chat, unknown, mismatch, and tampering.
- Tests cover create/import, offline/write failure and pending retry, reopen,
  legacy convergence without invitee inference, valid cache, cache miss/corruption,
  listing failure/retry, and cache invalidation.
- Tests prove exact private recipients/no bleed and unchanged General/public/DM;
  cover stale races and access backup migration/v2 decode/rollback/exclusion/republish.
- UI tests/checks cover truthful pending/Ready/Unavailable status, retry, and
  accessibility. Run full project gates, including Gradle tests, ktlint, and
  Android lint. Luna implements; Sol reviews. Device validation is a separate
  release gate after v0.24.1 and before any removal/revocation feature.

## Scope boundary

Do not change bearer-invite access semantics, add trust authority or claim
acknowledgement/atomicity, or alter General/public/DM behavior. Stop if required.
Protocol/architecture updates may specify only this approved protocol.
