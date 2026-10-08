# Background delivery surface

## Boundary and consumers

This surface covers periodic sync, configured short-interval fast polling, and delivery of queued outgoing messages. `SyncRunner` owns the shared sync cycle used by foreground and background triggers; Room remains the durable local message/outbox store.

## Observable behavior and interfaces

**Periodic recovery:** WorkManager schedules periodic sync using the member/community effective interval, with its own 15-minute repeat-interval floor. The operating system may defer work; the interval is not an exact delivery-time guarantee.

**Fast polling:** When the effective member/community interval is below WorkManager's 15-minute floor, the app uses `FastPollService` with a foreground-service notification and declared foreground-service permissions. The effective interval and enabled state survive process death. The manager retains periodic WorkManager scheduling while starting/restoring the service, and leaves it scheduled as a recovery path if foreground startup is rejected or restricted. At or above the WorkManager floor, periodic work is used without the fast service.

**Shared multi-community sync:** Both worker and service call the same `SyncRunner` cycle. It enumerates joined communities and their registered/discovered chat IDs; network work is not limited to the currently open chat. Newly discovered group chats become eligible for subsequent cycles. Polls and sends serialize with account restore. Credential-rotation commits and onboarding/account-store mutations serialize with restore and the local account-stability gate. Host-rotation network reads/writes run outside that gate against a captured community owner; commit revalidates the durable account generation, anchor, key, and runtime/selection context, so a community switch cannot redirect credentials and a concurrent restore invalidates the pending commit. Local chat open/install uses the account-stability gate without waiting on network poll or host-rotation I/O.

Foreground group/DM opens install the local runtime and Room feed before reading the WebDAV member directory. Verified roster enrichment runs asynchronously and is scoped to the request token, exact runtime graph, community, chat, and selection revision; stale results/errors are discarded, and cancellation propagates. Directory failure does not close or replace the feed.

**Outgoing outbox:** A retryable envelope, recipients, and local community owner are persisted with the message before network delivery. A new interactive send may build and persist an envelope only from the active chat's Ready verified-recipient snapshot; the same immutable snapshot supplies durable recipients and immediate change-index fan-out. Loading/Unavailable readiness creates no envelope or outbox row. Automatic cycle retry and explicit retry require that community owner and reuse the exact already-persisted envelope and message ID regardless of current roster readiness, even when another community uses the same chat ID. A conditional Room claim records a unique attempt token and allows only one delivery attempt per message ID at a time. Retry success/failure transitions require the exact attempt token plus its retry payload; initial-send transitions are limited to rows whose claim token is still null. Success removes the payload, and stale cleanup from a contender or earlier attempt cannot release a newer claim or downgrade a sent/read row. Cancellation releases only its own claim to FAILED in a non-cancellable cleanup, including cancellation during claim acquisition, then propagates cancellation. Process initialization recovers interrupted claims before publishing the runner or scheduling delivery, so recovery cannot revoke a fresh attempt.

Legacy outbox rows created before community ownership existed remain ownerless and are not automatically or manually retried rather than risking delivery to the wrong WebDAV root. Their payload and message history remain in the local unscoped legacy namespace; the history-repair pass never reassigns rows carrying an outbox envelope. Non-outbox legacy history and cursors may be repaired to the sole distinct joined community proven by the durable registry plus enumerated stored connections (or a single stored legacy connection with a joined-chat marker when the registry is empty). Any unreadable or unclassifiable enumerated connection fails repair closed; with multiple or no joined communities they remain unassigned. A message-ID collision preserves the legacy copy without replacing the already-owned row, and cursor collisions retain the lexicographically later order token. Rows whose community owner is already persisted retain that namespace across schema migration.

## Failure behavior and limits

If the platform denies or interrupts foreground-service startup, periodic work remains scheduled. A service notification can be hidden when notification permission is denied, but this does not itself disable service execution. On Android 13+, Settings explains the notification benefit and offers a contextual permission request; after denial, the user is guided to system notification settings rather than repeatedly prompted. Older Android versions do not request this runtime permission. Android battery controls, force-stop, network availability, WebDAV availability, and WorkManager scheduling remain outside the app's timing guarantee; no exact background delivery latency is promised.

## Security and non-goals

Credentials, identity keys, and message plaintext are not placed in WorkManager input data or notifications. Existing WebDAV and crypto protocols are unchanged. This contract does not promise execution after force-stop, real-time delivery, or guaranteed OS scheduling.

## Related surfaces

- [Chat surface](chat-surface.md)
- [Account recovery](account-recovery.md)
- [Community settings](community-settings.md)
