# Changelog

All notable changes to Open WebDAV Messenger are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Pre-1.0: these releases are backend substrates with no end-user UI yet — the public
surface is not stable, and minor versions may change behavior freely.

## [0.5.0] — 2026-06-04

First versioned release. Establishes the baseline after five backend substrates and the
2026-06-04 protocol-compliance audit. No user-facing UI yet — this is the encrypted
sync-and-storage foundation the chat surface will sit on.

### Added

- **Background message sync** (`sync`) — the engine that moves messages over the WebDAV
  disk without a server. New on-disk layout (protocol generation 1 → 2): a shared
  per-chat message log, a small per-member change index, and a reserved retention window.
  Sending writes one copy to the shared log plus a tiny per-member note (no longer one
  full copy per recipient). A background poll runs on a roughly 15-minute floor and
  catches up on anything missed while offline, within the retention window. Received
  messages are kept in a local on-device history (Room); per-chat keys are cached in
  memory for the session. Backend substrate — no UI.
- **Signed message format** (`message-model`) — a versioned, signed plaintext format for
  the two message kinds in scope (text and reaction); unknown or malformed messages are
  rejected rather than guessed.
- **Identity substrate** (`identity`) — per-user key material (X25519 for key agreement,
  Ed25519 for signing), sealed-box delivery, and a human-comparable fingerprint, for the
  future contact-verification feature.
- **End-to-end encryption substrate** (`crypto`) — client-side AEAD (XChaCha20-Poly1305)
  with keys derived from a per-chat passphrase via a memory-hard KDF (Argon2id), backed
  by audited native libraries; secret keys are wrapped with the Android Keystore and
  never written to the WebDAV disk. The disk operator sees ciphertext only.
- **WebDAV transport layer** (`webdav-transport`) — the OkHttp-based client and the
  authoritative on-disk protocol specification (`docs/protocol/webdav-layout.md`) that
  the cloud-disk transport is built on.

### Security

- Authored the project threat model (`docs/threat-model.md`) with stable `SCn`
  security-constraint identifiers, wiring documented threats to enforceable constraints.

### Documentation

- Completed the 2026-06-04 protocol-compliance audit and closed its follow-up notes
  (threat-model traceability and the per-feature review trail).

### Known limitations

- Retention-window pruning is reserved in the protocol but not yet implemented.
- The on-device sync engine is not yet wired into app startup (deferred, tracked in
  execution state).
- Local message history is not yet encrypted at rest (backlogged: SC17 / T16).
- On-device instrumented tests (`connectedAndroidTest`) are device-blocked in the current
  environment (open decision #8); 127 JVM tests pass.
