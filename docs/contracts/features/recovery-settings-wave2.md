# Recovery and settings Wave 2

> Status: approved; implementation in progress. Date: 2026-10-06.

## Goal

Deliver approved recovery, community-scoped settings, failure reporting, Android notification permission, and accessible retry improvements without changing existing message or WebDAV protocols.

## Scenarios

- Export and restore a joined multi-community account; legacy backups remain restorable where safe.
- Restore is available at first launch and activates usable runtime/background delivery.
- Invalid input or persistence failure does not leave mixed restored state; failures are typed.
- Community host metadata/settings follow community selection; personal appearance settings remain global.
- Remote policy writes commit in UI only after successful WebDAV confirmation, with deterministic concurrent writes.
- Android 13+ notification permission can be requested contextually and recovered from denial without repeated prompts.
- Failed-message retry is labelled, button-semantic, and at least 48dp without changing retry protocol.

## Non-goals

Invite wire/auth/expiry, group invite semantics, read-receipt protocol, crypto/WebDAV wire, update installer, broad redesign, and device deployment.

## Affected surfaces

- [Chat](../surfaces/chat-surface.md)
- [Background delivery](../surfaces/background-delivery.md)
- New durable [account recovery](../surfaces/account-recovery.md) and [community settings](../surfaces/community-settings.md).

## Interfaces and constraints

Preserve Wave 1 community isolation/outbox guarantees, Room as feed source, plain-text rendering, secret boundaries, and Android/Kotlin conventions. Restore validates before write and uses rollback/snapshot semantics appropriate to Keystore/files; do not claim crash atomicity. Remote settings failures are typed. No secrets in logs.

## Acceptance and validation

Deterministic tests cover multi-community round-trip, legacy restore, first-launch restore/runtime activation, malformed/store-failure rollback, community switching, typed WebDAV failures/concurrency, notification permission policy, and retry accessibility where supported. Run `./gradlew test ktlintCheck lint :app:compileDebugAndroidTestKotlin --no-watch-fs`, schema sanity, and `git diff --check`; report device validation availability.

## Status

Contract approved by task direction; implementation and verification are in progress.
