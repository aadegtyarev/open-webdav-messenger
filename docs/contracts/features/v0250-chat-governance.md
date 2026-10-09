# v0.25.0 Chat Governance and Deletion

Status: **Approved** — 2026-10-09

## Goal and scope

- General is undeletable. Group remote delete-for-all is authorized by a verified pinned host, or by the immutable creator origin for new groups. Legacy, missing-origin, and conflicting-origin groups are host-only; creator authorization fails closed on conflict.
- Any DM participant may delete locally. Suppress exactly the pre-delete message IDs, purge local history/outbox/cursor state transactionally, retain the derived DM key, and reopen on the first genuinely new peer message with only new history. New DMs bind a signed, DM-key-authenticated initiator origin; only its verified initiator may delete-for-both. Legacy/missing/conflicting origins permit local deletion only. A host never controls a DM.

## Host trust

New-community creator genesis pins the host signing key into the invite and durable encrypted local registry. An existing community publishes genesis; each member must explicitly verify its short fingerprint or import a fresh governance invite. Until verified, host deletion and policy actions fail closed. Self-signed `community.json` and local `isHost` are not authority. No silent host-key replacement: rotation/transfer is out of scope unless an old-key-signed chain is implemented and contracted. Backup preserves pinned key, verification, and revisions safely; restore never elevates trust.

## Origins and creation policy

- Group origins are immutable, content-addressed, canonical, domain-separated signed records binding exact community/chat/access/creator identities and nonce. Conflicting origins never select a winner. New-group publication transactionally fences local registration against origin outcome as feasible and reports partial results truthfully.
- Host-signed creation policy has monotonic revision, independent `allowPublic`/`allowPrivate`, and a bounded grandfather snapshot of immutable origin hashes. With no trusted policy ever advertised (legacy included), both types remain allowed. Once a trusted policy existed, missing/unavailable/rollback/equivocation fails closed for new creation, while existing chats remain open. Disabling a type requires a completed verified origin listing; its snapshot preserves existing origins. Updated clients ignore prohibited post-policy origins not grandfathered. Old clients/shared credentials can bypass this cooperative policy; this is irreducible.

## Tombstones, local deletion, and cleanup

- Community-scope append-only content-addressed signed tombstones bind domain/version, exact community/chat/origin, scope, revision, and signer role. Group creator authority requires exact origin or pinned host authority. DM tombstones additionally authenticate with the DM key and bind exact participant pair and initiator origin. Retain origins, genesis, and tombstones indefinitely; never cleanup-delete them. One valid tombstone permanently closes its origin; exact duplicates are idempotent, conflicts fail closed, and cross-context/replay is rejected.
- Persist local graveyard before remote work. Tombstone PUT success is not peer observation. After successful PUT, hide chat, reject sends, fence subscriptions/outbox/in-flight claims, and return an active screen to the list. Cleanup best-effort deletes descriptor revisions, logs, changes/notifies, receipts, private membership, and remote chat content; 404 is success, other failures get durable retry. Surface local committed / tombstone published / cleanup pending / complete. Partial cleanup never resurrects. Deletion is best-effort after tombstone, not crash-atomic remote cleanup.
- Offline clients reconcile tombstones before discovery/open/poll; on observation purge local history, key, cache, pending state, and outbox. DM suppression records exact pre-delete message IDs; only genuinely new peer history reopens it. Secure erase, downloaded exports, and old clients cannot be guaranteed.
- Local transactions/migration, backup/restore/graveyard/hidden suppression, account/community/chat generation fences, lock ordering, and worker fencing must prevent stale work from recreating deleted state.

## UX

Accessible list/overflow actions, typed/title confirmation, authority-based visibility, progress and retry. Community settings expose two independent host-only toggles and clear trust/policy/fingerprint state plus governance-invite migration. Local DM deletion is always accessible.

## Non-goals

Participant removal/revocation (v0.26); host control of DMs; erasure of peer exports or secure flash; prevention of raw WebDAV abuse or legacy-client bypass.

## Affected contracts and documentation

Durable surfaces: [chat](../surfaces/chat-surface.md), [community settings](../surfaces/community-settings.md), [background delivery](../surfaces/background-delivery.md), [account recovery](../surfaces/account-recovery.md). Also update `docs/protocol/webdav-layout.md` (§11+ and additive governance formats), `docs/architecture.md`, and user journeys. Keep the surfaces and protocol/architecture trust caveats aligned.

## Acceptance criteria

Adversarial codec/crypto/path/bounds; fake self-signed host; rollback/equivocation; creator conflicts; legacy records; tombstone priority, replay and cross-context rejection; partial cleanup/retry/404; bounded-work fairness; offline reconciliation; active deletion/navigation; Room migration; backup versions/restore without trust elevation; DM exact replay suppression and new-message reopen; concurrent sends/outbox/account restore; public/private policy matrix and accessibility. Verify no stale worker can recreate state and partial cleanup cannot resurrect a tombstoned chat.

## Validation and release

Run full project gates and device validation. Target v0.25.0 / code 60; Luna implements, Sol reviews; release, then device validation. No tests or implementation are part of this contract-recording task.
