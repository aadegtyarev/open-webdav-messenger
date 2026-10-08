# Community settings surface

## Boundary

This surface covers community-owned host role and remote polling/retention policy, plus its interaction with global personal preferences.

## Consumers

Community hosts change retention and minimum polling interval. Members read the signed policy. The settings UI and shared runtime use per-community local metadata caches.

## Observable behavior

- Host status, cached retention, and community poll floor are keyed by community ID. Switching communities selects that community's role and metadata; personal display name, appearance, and selected member interval remain user-global.
- Remote policy changes are serialized per community. Only a successful WebDAV collection/write result updates the local committed cache or the selected UI value. Rejected or failed writes are surfaced; a superseded queued write does not become the final policy.
- Personal appearance preferences are independent of community policy. Polling remains subject to WorkManager/Android scheduling floors and platform restrictions.

## Failure and recovery

A WebDAV rejection or I/O failure leaves the prior committed remote policy visible and reports an error. The user can retry. Client-side poll-floor enforcement is cooperative, not server-enforced.

## Security and non-goals

Community metadata remains signed by the host identity and stored in the existing WebDAV metadata file. This contract does not redesign signature, invite, membership, or WebDAV wire formats. Disk operators can observe collection/file metadata as documented in the threat model.

## Related contracts

- [Chat surface](chat-surface.md)
- [Background delivery](background-delivery.md)
- [Account recovery](account-recovery.md)
