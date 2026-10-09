# Community settings surface

## Boundary

This surface covers community-owned host role and remote polling/retention policy, plus its interaction with global personal preferences.

## Consumers

Community hosts change retention and minimum polling interval. Members read the signed policy. The settings UI and shared runtime use per-community local metadata caches.

## Observable behavior

- Host status, cached retention, and community poll floor are keyed by community ID. Switching communities selects that community's role and metadata; personal display name, appearance, and selected member interval remain user-global. A legacy global community-setting value is migrated only when exactly one registered community makes its owner unambiguous; otherwise role is not guessed across communities.
- Remote policy changes are serialized per community. Field-level edits merge into a combined pending policy, so a rapid retention change followed by a poll-floor change preserves both requested values. A host's detached initial default-policy PUT participates in this same ordering; a delayed default cannot overwrite a newer setting, and onboarding roster/default writes are discarded after runtime replacement. Reset invalidates pending request tokens without reusing their identities, so stale completions cannot clear a new runtime's request. Only a successful WebDAV collection/write result updates the local committed cache or selected UI values. Rejected or failed writes are surfaced; a superseded queued write does not become the final policy.
- Personal appearance preferences are independent of community policy. Polling remains subject to WorkManager/Android scheduling floors and platform restrictions.

## Failure and recovery

A WebDAV rejection or I/O failure leaves the prior committed remote policy visible and reports an error. The user can retry. Client-side poll-floor enforcement is cooperative, not server-enforced.

## Security and non-goals

Community metadata remains signed by the host identity and stored in the existing WebDAV metadata file. This contract does not redesign signature, invite, membership, or WebDAV wire formats. Disk operators can observe collection/file metadata as documented in the threat model.

## Governance settings (v0.25.0)

- Existing communities can publish host-signed genesis only through an explicit
  migration action. Members verify the short host fingerprint or import a fresh
  governance invite; self-signed metadata and the local host toggle are not
  authority.
- A verified host can independently allow/deny public and private group
  creation. Disabling a type first completes a bounded verified origin scan and
  stores an immutable grandfather snapshot. Revision/high-water conflicts or
  unavailable publication leave prior state unchanged and offer retry.
- Policy controls are hidden or read-only when host authority is unverified.
  The host-exemption behavior is disclosed: policy also applies to host-created
  groups. Existing groups remain open when creation is disabled.
- See [WebDAV protocol §13](../../protocol/webdav-layout.md#13-community-governance-origins-and-tombstones)
  for trust and compatibility limits.

## Related contracts

- [Chat surface](chat-surface.md)
- [Background delivery](background-delivery.md)
- [Account recovery](account-recovery.md)
