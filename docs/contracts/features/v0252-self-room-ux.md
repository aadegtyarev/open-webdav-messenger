# v0.25.2 self-room UX patch

> Status: approved; temporary feature contract for implementation and final verification.

## Goal and behavior

A locally created private room must be usable by its creator before another member joins, while remote membership remains verified and all send guarantees remain intact. The feed's toolbar, send indicators, and accessibility semantics must communicate operation-specific state without duplicate visible status text.

## Scenarios and constraints

- A locally-created private room with no verified non-self member may become `Ready(self)` without waiting for a remote claim listing. Remote membership scanning still runs; verified claims may add members. Imported, joined, and ambiguous rooms remain fail-closed until verified remote readiness. Once any non-self member has been observed, retain the cached exact-roster rules; do not use the self-only exception to replace or broaden that roster.
- Exercise production self-send end to end: ChatFeedScreen → ViewModel → Room/outbox → WebDAV append. Verify durable local echo, restart/recreation persistence, retry with the original message ID/envelope, idempotency/no duplicate rows or writes, and exact recipients after a member joins. `Sent` means only that the WebDAV write succeeded, not peer delivery or receipt.
- Across recipient-roster and delete-authority loading, show at most one toolbar progress indicator. Keep action icons stable and disabled as appropriate, expose operation-specific accessible names, and keep message refresh distinct from loading/retry actions.
- Outgoing status is icon-only visually: queued/sending clock or progress, sent check, failed warning with Retry. Do not show a permanent visible status word. Supply localized accessibility names (including Russian), and do not rely on color alone. Retry remains an accessible target of at least 48×48dp.
- Test at real `ChatFeedScreen` semantics: empty self-room composer enabled; one spinner maximum; stable toolbar actions; Queue→Sent and Queue→Failed transitions; Retry target; no duplicate status text; Russian semantics; recreation/restart persistence. Cover every combination of roster and delete-authority loading, plus unavailable/retry route. Physical IME and TalkBack behavior and the manual device checklist are explicitly unverified without a device; do not claim them verified by Compose tests.
- Investigate the concrete peer-send path when a peer does not receive a message: check the persisted exact recipients/envelope, append result, and subsequent peer polling/read path. Add diagnostic acceptance evidence. Do not promise or fix an un-reproduced peer nonreceipt; pause and obtain scope approval if investigation requires changing peer delivery behavior.

## Non-goals

Delivered/read receipts; inviting unverified users; broad localization redesign; attachment UX; wire-format changes; fixes to unknown peer nonreceipt without reproduction and approval.

## Affected surface contracts

- [Chat surface](../surfaces/chat-surface.md) — readiness, toolbar, feed semantics, send UI.
- [Background delivery](../surfaces/background-delivery.md) — durable outbox, retry, WebDAV delivery boundary.

## Acceptance criteria

1. Only qualifying locally-created private rooms get self-only Ready before remote claims; remote scans continue and only verified claims extend membership. Imported/joined/ambiguous rooms fail closed; established exact cached rosters are preserved.
2. Production self-send persists before append, survives restart, reaches truthful queued/sent/failed states, retries the same durable operation without duplication, and uses the exact verified recipient snapshot when a peer joins.
3. Toolbar loading/action behavior and status visuals/accessibility meet the scenarios above, including Russian semantics and every loading/unavailable combination.
4. Feed semantics tests prove all automatable criteria. Record IME/TalkBack/device checks as unverified absent physical-device evidence; record peer-path diagnostic observations without asserting peer receipt.

## Validation

Run the full Android gates required by `AGENTS.md`: Gradle tests, ktlint, and Android lint. Include targeted real-screen semantics and persistence/outbox tests. Do not claim physical-device checks unless executed on a device.
