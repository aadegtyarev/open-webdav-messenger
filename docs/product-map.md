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

| Feature | Done | Review |
|---|---|---|---|
| [webdav-transport](features/webdav-transport_plan.md) | 2026-06-03 | (transient, deleted after ship) |
| [crypto](features/crypto_plan.md) | 2026-06-03 | (transient, deleted after ship) |
| [identity](features/identity_plan.md) | 2026-06-03 | (transient, deleted after ship) |
| [message-model](features/message-model_plan.md) | 2026-06-04 | (transient, deleted after ship) |
| [sync](features/sync_plan.md) | 2026-06-04 | (transient, deleted after ship) |
| [directory](features/directory_plan.md) | 2026-06-04 | (transient, deleted after ship) |
| [chat-directory](features/chat-directory_plan.md) | 2026-06-04 | (transient, deleted after ship) |
| [release-ci](features/release-ci_plan.md) | 2026-06-05 | (transient, deleted after ship) |
| [identity-store-io-dispatch](features/identity-store-io-dispatch_plan.md) | 2026-06-05 | (transient, deleted after ship) |
| [codec-dedup-and-send-hardening](features/codec-dedup-and-send-hardening_plan.md) | 2026-06-06 | (transient, deleted after ship) |
| [x25519-identity](features/x25519-identity_plan.md) | 2026-06-06 | (transient, deleted after ship) |
| compression (v0.10.0) | 2026-06-14 | (autonomous, plan not preserved) |
| history-encryption (v0.11.0) | 2026-06-14 | (autonomous, plan not preserved) |
| foreground-polling (v0.12.0) | 2026-06-14 | (autonomous, plan not preserved) |
| export-restore (v0.13.0) | 2026-06-14 | (autonomous, plan not preserved) |
| [ui-chat-surface](features/ui-chat-surface_plan.md) (v0.14.0) | 2026-06-15 | Pass-1 + Pass-2 (merged) |
