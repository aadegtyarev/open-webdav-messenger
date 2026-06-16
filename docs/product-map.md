# Product map — what the system does, by contract

> Status: **live** — contract is in force · **deprecated** — superseded, kept for history.

> Generated from contracts + plans + reviews + git (regenerated as features land). Seven backend substrates plus a CI/release pipeline have landed; no user-facing Product Contracts exist yet (these features are system-subject — contract blocks appear here once a user-facing feature is planned). The component groups below come from `docs/architecture.md`.

## UI (Compose chat surface)

- [chat-surface](contracts/chat-surface.md) — onboarding flow, invite format, chat feed (v0.14.0)

## Transport (WebDAV over OkHttp)

_No contracts yet._

## Protocol (inbox fan-out, message envelope)

_No contracts yet._

## Crypto (libsodium AEAD + Argon2id KDF)

_No contracts yet._

## Compression codec (DEFLATE)

_No contracts yet._

## Markdown rendering

_No contracts yet._

## Local cache (Room)

_No contracts yet._

## Background sync (WorkManager)

_No contracts yet._

## Infrastructure (no user-facing contract)

| Feature | Shipped | Plan |
|---|---|---|---|
| webdav-transport | 2026-06-03 | deleted |
| crypto | 2026-06-03 | deleted |
| identity | 2026-06-03 | deleted |
| message-model | 2026-06-04 | deleted |
| sync | 2026-06-04 | deleted |
| directory | 2026-06-04 | deleted |
| chat-directory | 2026-06-04 | deleted |
| release-ci | 2026-06-05 | deleted |
| identity-store-io-dispatch | 2026-06-05 | deleted |
| codec-dedup-and-send-hardening | 2026-06-06 | deleted |
| x25519-identity | 2026-06-06 | deleted |
| compression (v0.10.0) | 2026-06-14 | autonomous |
| history-encryption (v0.11.0) | 2026-06-14 | autonomous |
| foreground-polling (v0.12.0) | 2026-06-14 | autonomous |
| export-restore (v0.13.0) | 2026-06-14 | autonomous |
| ui-chat-surface (v0.14.0) | 2026-06-15 | deleted |
