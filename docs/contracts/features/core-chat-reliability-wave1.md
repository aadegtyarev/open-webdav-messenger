# Core chat reliability — Wave 1

> Status: approved (2026-11-22)

## Goal

Make chat identity, offline sending, community selection and background delivery reliable across navigation, process death and transient failures, without changing the wire protocol.

## Scenarios and edge cases

- Switching community/chat scopes feed and invite state and sends only to the selected destination.
- A cold start restores the active community/runtime without forcing a chat open; background sync resumes and covers every joined community plus discovered chats.
- Offline or uncertain sends retain one durable original envelope/ID; automatic and manual retry reuse it, preserve truthful status, and cannot overwrite a newer draft after asynchronous failure. Clear draft only after recoverable local persistence.
- Group creation uses the selected community's graph, transport and identity, then opens there.
- Fast polling has the base foreground permission; restricted/failed service start preserves or recovers periodic WorkManager delivery until service start is confirmed.
- System Back is consistent; recreation preserves destination/form intent, first-launch toolbar Back reaches Start, feed Back reaches Chats.
- Feed follows appended messages only when previously at/near bottom; read marking observes latest visible message IDs/state.

## Non-goals

Export/restore redesign; settings, notification or accessibility work; invite format/security or group-invite semantics; read-receipt protocol redesign; crypto/protocol changes; broad visual redesign.

## Affected surfaces

- [Chat surface](../surfaces/chat-surface.md)
- [Background delivery](../surfaces/background-delivery.md) (create after verified implementation)

## Interfaces and constraints

Room remains the only feed source; render literal plaintext; preserve existing crypto, WebDAV protocol and message ordering. Android/Kotlin conventions; no secrets. Scope every runtime-dependent UI action by community and chat. Do not cancel periodic work before confirmed foreground-service startup. If implementation requires a non-goal or changes another surface/acceptance boundary, stop for approval.

## Acceptance

Deterministic unit/Robolectric coverage for scoped feed/invite, cold-start selection/runtime, multi-community/discovered-chat sync, original-ID retries including uncertain outcomes, draft races, selected-community creation, fast-poll fallback, Back/recreation, conditional auto-scroll and latest-state read marking. No wrong-community display/send, duplicate local rows/content, lost recoverable draft, or false terminal status.

## Validation

Run targeted Gradle tests, full `./gradlew test`, `./gradlew ktlintCheck`, `./gradlew lint`, schema sanity and `git diff --check`. Report unavailable instrumentation/device checks without claiming them. Compare implementation and durable surfaces against this contract before completion.
