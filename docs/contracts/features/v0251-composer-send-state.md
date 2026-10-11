# v0.25.1 Composer and send state

> Status: approved for implementation; temporary feature contract.

## Goal and behavior

Modern optimistic messaging UX: once the exact submitted draft is locally persisted, clear that draft promptly and show one local bubble with truthful progress. This adopts an optimistic interaction pattern only; it does not promise parity with any external messaging app.

## Scenarios

- After Room/outbox persistence succeeds, clear only the exact draft submitted. A delayed `onValueChange` carrying pre-send text must not restore it; edits made after submission must survive persistence completion.
- Persisted outgoing content appears as exactly one local bubble. Show visible and accessible state labels: Queue/Queued (`В очереди` in Russian) while awaiting completion, Sent (`Отправлено`) after send completion, and Failed (`Не отправлено`) on failure. Failed bubbles provide a retry action.
- Persistence failure retains the composer draft. A later network failure updates the bubble but never repopulates the composer, including when a newer draft has been entered.
- Duplicate-tap reservation and tombstone/deletion fences remain fail-closed: do not create a duplicate or send through a closed chat.

## Non-goals

- No delivered, peer-received, read, or seen claim; Sent describes only this client's send completion.
- No wire/envelope/message-ID change, second feed list or persistence path, replacement-message retry, or changes to roster, sync, or deletion policy.
- No external-app parity guarantee, global connectivity indicator, or redesign of chat layout.

## Affected surfaces

- [Chat feed surface](../surfaces/chat-surface.md), specifically its feed/composer, local outgoing bubble state, and send failure/retry behavior. No other surface contract is changed by this scope.

## Interfaces and constraints

- Preserve the Room-backed message flow and existing durable outbox/send service; local persistence remains the boundary for clearing the submitted draft.
- Draft clear and UI edits are ordered on the UI state path and guarded against both stale pre-send callbacks and post-send user edits.
- Queue/Queued, Sent, and Failed labels are both visible and exposed to accessibility; retry remains attached to the failed bubble.
- Keep duplicate send reservation, account/chat scoping, and tombstone/deletion checks fail-closed.

## Acceptance criteria

- Regression tests prove exact-submitted-draft clear after persistence; stale pre-send callback cannot restore; post-send edits survive.
- Tests prove one local bubble and visible/accessibility labels for all three states, plus retry from a failed bubble without a new row or envelope.
- Tests prove persistence failure retains draft, network failure does not restore it, duplicate taps yield one send, and tombstone fences reject sending.
- No UI state claims delivery/read, and existing Room feed remains the sole message source.

## Validation

- Run focused ChatFeedScreen, ChatFeedViewModel send-failure, and MessageSendService tests, then the relevant Gradle unit-test task.
- Run ktlint and Android lint for the implementation change; inspect the diff against this contract and the linked chat surface.
