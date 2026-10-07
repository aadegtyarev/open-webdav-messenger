# Background delivery surface

## Boundary and consumers

This surface covers periodic sync, configured short-interval fast polling, and delivery of queued outgoing messages. `SyncRunner` owns the shared sync cycle used by foreground and background triggers; Room remains the durable local message/outbox store.

## Observable behavior and interfaces

**Periodic recovery:** WorkManager schedules periodic sync using the member/community effective interval, with its own 15-minute repeat-interval floor. The operating system may defer work; the interval is not an exact delivery-time guarantee.

**Fast polling:** When the effective member/community interval is below WorkManager's 15-minute floor, the app uses `FastPollService` with a foreground-service notification and declared foreground-service permissions. The effective interval and enabled state survive process death. The manager retains periodic WorkManager scheduling while starting/restoring the service, and leaves it scheduled as a recovery path if foreground startup is rejected or restricted. At or above the WorkManager floor, periodic work is used without the fast service.

**Shared multi-community sync:** Both worker and service call the same `SyncRunner` cycle. It enumerates joined communities and their registered/discovered chat IDs; network work is not limited to the currently open chat. Newly discovered group chats become eligible for subsequent cycles.

**Outgoing outbox:** A retryable envelope, recipients, and local community owner are persisted with the message before network delivery. Automatic cycle retry and explicit retry require that community owner and reuse the exact envelope and message ID, even when another community uses the same chat ID. A conditional Room claim records a unique attempt token and allows only one delivery attempt per message ID at a time. Retry success/failure transitions require the exact attempt token plus its retry payload; initial-send transitions are limited to rows whose claim token is still null. Success removes the payload, and stale cleanup from a contender or earlier attempt cannot release a newer claim or downgrade a sent/read row. Cancellation releases only its own claim to FAILED in a non-cancellable cleanup, including cancellation during claim acquisition, then propagates cancellation. Process initialization recovers interrupted claims before publishing the runner or scheduling delivery, so recovery cannot revoke a fresh attempt.

Legacy outbox rows created before community ownership existed remain ownerless and are not automatically or manually retried rather than risking delivery to the wrong WebDAV root. Their payload and message history are retained in a local unscoped legacy namespace, not shown in a joined feed and not assigned an inferred owner when multiple communities may share a chat ID. Rows whose community owner is already persisted retain that namespace across schema migration.

## Failure behavior and limits

If the platform denies or interrupts foreground-service startup, periodic work remains scheduled. A service notification can be hidden when notification permission is denied, but this does not itself disable service execution. Android battery controls, force-stop, network availability, WebDAV availability, and WorkManager scheduling remain outside the app's timing guarantee; no exact background delivery latency is promised.

## Security and non-goals

Credentials, identity keys, and message plaintext are not placed in WorkManager input data or notifications. Existing WebDAV and crypto protocols are unchanged. This contract does not promise execution after force-stop, real-time delivery, or guaranteed OS scheduling.

## Related surfaces

- [Chat surface](chat-surface.md)
