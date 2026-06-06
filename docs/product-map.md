# Product map — what the system does, by contract

> Status: **live** — contract is in force · **deprecated** — superseded, kept for history.

> Generated from contracts + plans + reviews + git (regenerated as features land). Seven backend substrates plus a CI/release pipeline have landed; no user-facing Product Contracts exist yet (these features are system-subject — contract blocks appear here once a user-facing feature is planned). The component groups below come from `docs/architecture.md`.

## UI (Compose chat surface)

_No contracts yet._

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
|---|---|---|
| [webdav-transport](features/webdav-transport_plan.md) | 2026-06-03 | [R](../.ai-pm/reviews/webdav-transport_review.md) |
| [crypto](features/crypto_plan.md) | 2026-06-03 | [R](../.ai-pm/reviews/crypto_review.md) |
| [identity](features/identity_plan.md) | 2026-06-03 | [R](../.ai-pm/reviews/identity_review.md) |
| [message-model](features/message-model_plan.md) | 2026-06-04 | [R](../.ai-pm/reviews/message-model_review.md) |
| [sync](features/sync_plan.md) | 2026-06-04 | [R](../.ai-pm/reviews/sync_review.md) |
| [directory](features/directory_plan.md) | 2026-06-04 | [R](../.ai-pm/reviews/directory_review.md) |
| [chat-directory](features/chat-directory_plan.md) | 2026-06-04 | [R](../.ai-pm/reviews/chat-directory_review.md) |
| [release-ci](features/release-ci_plan.md) | 2026-06-05 | [R](../.ai-pm/reviews/release-ci_review.md) |
| [identity-store-io-dispatch](features/identity-store-io-dispatch_plan.md) | 2026-06-05 | [R](../.ai-pm/reviews/identity-store-io-dispatch_review.md) |
| [codec-dedup-and-send-hardening](features/codec-dedup-and-send-hardening_plan.md) | 2026-06-06 | [R](../.ai-pm/reviews/codec-dedup-and-send-hardening_review.md) |
