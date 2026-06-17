# Changelog

All notable changes to Open WebDAV Messenger are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Pre-1.0: the public surface is not stable, and minor versions may change behavior freely.

## [0.20.0] — 2026-06-17

### Added

- "New messages" divider in chat feed — a labeled horizontal rule between
  read and unread messages. On first open, scrolls to the first unread.
  Messages are progressively marked as READ as the user scrolls down.

### Changed

- Chat feed scroll behavior: opens at first unread message (was: bottom).

## [0.19.0] — 2026-06-17

### Added

- Unread badges on community list — each community shows a badge with the
  count of unread messages (sendStatus = 'SENT').

### Fixed

- Messages are now marked as READ when the chat feed opens. Previously
  `markRead()` was never called, so badges would accumulate forever.

## [0.18.1] — 2026-06-17

### Fixed

- Sender names now show display names instead of hex-key prefixes. The
  community directory was always empty because `publishEntry()` was never
  called; now it publishes the member entry during each poll cycle.

## [0.18.0] — 2026-06-17

### Security

- ExportRestoreActivity no longer exported — external apps cannot launch the
  key-material-handling UI.

### Added

- Export / Restore entry point via Settings → Account section.

## [0.14.0] — 2026-06-15

### Added

- **First usable chat UI** built with Jetpack Compose. Users can now create a
  community, share an invite via QR code, and chat in a real-time message feed
  with auto-scroll. The full onboarding flow — create or join — is wired into the
  existing backend (key isolation, encryption at rest, compression, foreground
  polling). New modules under `app/.../ui/`: `onboarding/`, `invite/`, `feed/`,
  plus `keystore/ConnectionConfigStore` and engine wiring in `AppRoot`.

## [0.10.0] — 2026-06-14

### Added

- DEFLATE compression for message envelope (`codec-id = 0x01`). Messages are compressed
  with `java.util.zip` (raw DEFLATE, nowrap, BEST_COMPRESSION) before AEAD encryption,
  reducing bandwidth on rate-limited WebDAV transport. 1 MiB decompression bound guards
  against zip-bombs. Per-message independent compression prevents CRIME/BREACH-class leaks.
  New `app/.../codec/` module (CompressionCodec, DeflateCodec, Codec enum).

## [0.11.0] — 2026-06-14

### Added

- **Local Room DB encryption** — chat history is now encrypted at rest using
  SQLCipher (AES-256-CBC via `net.zetetic:android-database-sqlcipher`). The
  database passphrase is derived from the user's master key, so storage-level
  access (device seizure, backup leak) reveals no plaintext messages. The Room
  `SupportFactory` is replaced with `SupportFactory(passphrase)` at open time;
  existing unencrypted databases are migrated on first open.

## [0.12.0] — 2026-06-14

### Added

- **Foreground service for sub-15-minute polling** — an opt-in `FastPollService`
  that keeps a foreground notification alive, allowing WorkManager periodic sync
  to run at intervals shorter than the platform's 15-minute minimum for
  non-foreground work. The user sees a persistent "listening for messages"
  notification; the interval is configurable. `SyncScheduler` and
  `FastPollManager` coordinate the transition between background and foreground
  polling modes.

### Changed

- `AndroidManifest.xml` now declares `FOREGROUND_SERVICE_DATA_SYNC` permission
  and `POST_NOTIFICATIONS`.

## [0.13.0] — 2026-06-14

### Added

- **Account export and restore** — the user can export their entire local state
  (identity, rooms, messages, config) as a password-encrypted, zlib-compressed,
  integrity-checked blob (`ExportPayload`). Importing the blob on a new device
  restores the full account, enabling device-loss recovery without server-side
  backup. The format carries a version marker and is guarded by an Argon2id KDF
  against brute-force. New `export/` module (ExportManager, RestoreManager,
  ExportPayload, ExportableStores).

## [0.9.0] — 2026-06-06

A new production capability — remote private chats — plus a key-isolation fix. Still
a backend substrate: the verification UI and chat screens land with a later feature.
The security review found no correctness issues; the net direction is tightening (no
security claim weakened).

### Added

- **Remote private chats — start a sealed chat with someone you already share a disk
  with, no secret passed between you.** A member can now establish a private chat with
  a peer discovered through the directory: the app combines the peer's public key with
  the chat identifier to derive the chat key on each side independently, so nothing
  secret ever travels over the disk or any other channel. A native-crypto failure
  surfaces as a typed failure result rather than a crash.

### Fixed

- **Two private chats between the same pair of people no longer share a key.** Keys
  derived from a key-agreement handshake are now bound to the specific chat identifier,
  so each chat gets a distinct key. Previously two chats between the same identity pair
  could derive the same key (issue D10). The bare pairwise key-agreement primitive
  keeps its existing signature; only the chat-key derivation changed.

## [0.8.6] — 2026-06-06

Security-model hardening from a PM-directed decision (2026-06-06) — no functional
app-behavior change (docs, CI, and two KDoc comments only). The net direction is
tightening: no security claim is weakened, and the threat model gains coverage it
did not have before.

### Changed

- **"Public" chats are now community-keyed, not world-readable.** The well-known
  public-chat key is retired and no longer embedded in source. So-called public chats
  now use the community key — readable by every onboarded member but sealed from the
  disk operator. Previously the operator (and anyone with the link) could read public
  chats in plaintext; now they cannot. Architecture decision 9 is revised, along with
  the `public` axis of the chat taxonomy and security claims SC1/SC2/SC3, and threat
  T14 is updated.

### Added

- **No-secrets-in-source guarantee (SC21) + threat T27.** A new absolute security
  claim — no secret material in the source tree or anywhere in git history — backed by
  a new threat entry (T27, source-repo secret leakage).
- **gitleaks secret-scan CI gate.** A new `secret-scan` job in
  `.github/workflows/pr-checks.yml` (pinned gitleaks 8.30.1) scans the full git history
  and fails the pull request on any finding. It is wired into the AGENTS.md pipeline and
  the `docs/stack-notes.md` validators table, and runs on every pull request.

## [0.8.5] — 2026-06-06

Internal-quality refactor from the 2026-06-06 audit cycle — no end-user behavior
change. Three near-duplicate parse cursors collapse onto one shared bounded cursor,
the directory and chat-directory services fold onto a shared generic engine, and the
base32 alphabet plus AEAD framing sizes are now single-sourced. One additive hardening
(C8) closes a latent crash path on native encryption failure during publish.

### Changed

- **Codec parsing** — the three private parse cursors in `ChatDescriptorCodec`,
  `DirectoryEntryCodec`, and the message codec now route through one shared bounded
  `ByteCursor`. Overrun, negative-take, limit-ceiling, and 64-bit boundary handling are
  identical to before; the public parse APIs are unchanged.
- **Directory / chat-directory services** — the two near-clone services collapse onto a
  shared generic `CommunityDirectoryEngine`. Public `DirectoryService` /
  `ChatDirectoryService` types and method signatures are unchanged, and the two
  collections never cross-wire.
- **Base32 alphabet** — the RFC-4648 lowercase alphabet is centralized to one constant
  (`HashTag.BASE32_LOWER_CHARS`) referenced by all former call sites.
- **AEAD framing sizes** — nonce / tag / key byte sizes derive from the libsodium
  XChaCha20-Poly1305 constants instead of repeated literals.

### Fixed

- **Directory publish on native seal failure (C8)** — both `DirectoryService.publishEntry`
  and `ChatDirectoryService.publishChatEntry` now map a native AEAD seal failure to the
  caller's typed `Failed` result instead of letting an uncaught `IllegalStateException`
  propagate. Previously only `IllegalArgumentException` was caught, so a native seal
  failure could crash the publish path.

### Removed

- **Dead `destroy()` methods** — `ChatKey.destroy()` and `Identity.destroy()` had no
  remaining call sites and were removed.

## [0.8.4] — 2026-06-05

Dispatcher-safety fix for `IdentityStore.loadOrCreate()`: blocking I/O can no longer
run on the calling thread's dispatcher. A companion threading-contract hardening lands
in the same commit.

### Fixed

- **`IdentityStore.loadOrCreate()`** — wrapped body in `withContext(Dispatchers.IO)` so
  blocking Keystore and file I/O is always dispatched to the IO thread pool, regardless
  of which coroutine dispatcher the caller uses. Replaces the prior `synchronized` block
  with a coroutine-friendly `Mutex` to avoid thread pinning.
- **`IdentityStore` blocking methods** (`load`, `store`, `has`, `remove`) — annotated
  `@WorkerThread` so any future call from the main dispatcher produces a compile-time
  warning. `generateOnceMutex` KDoc now documents non-reentrancy to prevent deadlock.

## [0.8.3] — 2026-06-05

Two internal refactors from the quality-sweep cleanup pass. No behavior change.

### Changed

- **`PropfindParser`** — `DocumentBuilderFactory` is now configured once at object
  initialization instead of on every `parse()` call. The factory is thread-safe after
  configuration (JAXP 1.4 §5.2), so the shared instance is safe for concurrent use.
- **`ChatDescriptorCodec` / `DirectoryEntryCodec`** — removed private `writeUint16Be`
  and `writeUint64Be` helpers that were byte-for-byte duplicates of `BigEndian.writeUint16Be`
  and `BigEndian.writeUint64Be`. Call sites now use `BigEndian` directly; output is unchanged.

## [0.8.2] — 2026-06-05

Three correctness fixes from the audit-2026-06-05b quality sweep.
No behavior change on the happy path; error paths that previously crashed or
silently misbehaved now return typed failures.

### Fixed

- **`WebDavTransport.mapMultistatus`** — PROPFIND success guard changed from the
  unreachable `!isSuccessful && code != 207` to `code != 207`; a 200 OK with a
  non-multistatus body now correctly returns `TransportError` instead of being
  silently accepted.
- **`KeystoreWrapper.wrap()`** — body wrapped in `try/catch(Exception)` that
  rethrows as `IOException`, mirroring the existing `unwrap()` pattern; unhandled
  `KeyStoreException` / `GeneralSecurityException` can no longer escape as an
  unchecked crash.
- **`WebDavTransport.gate()`** — added OkHttp URL parseability validation; a
  malformed base URL now returns `TransportError` before any HTTP verb is
  attempted.

## [0.8.1] — 2026-06-05

Three correctness fixes that close unhandled-exception paths found in the
audit-2026-06-05b quality sweep. No behavior change on the happy path; error
paths that previously crashed are now returned as typed failures.

### Fixed

- **`PropfindParser.lastSegment()`** — wrap `URLDecoder.decode()` in
  `runCatching` so a malformed percent-sequence in a server-returned path
  returns the raw segment instead of throwing `IllegalArgumentException` and
  crashing the enclosing `pollCycle()`.
- **`DirectoryService.publishEntry()`** — add `catch(IllegalArgumentException)`
  before `finally`; returns `PublishOutcome.Failed(...)` on decode failure,
  upholding the "never throws" KDoc contract.
- **`ChatDirectoryService.publishChatEntry()`** — same pattern; returns
  `ChatPublishOutcome.Failed(...)` on decode failure.

## [0.8.0] — 2026-06-05

Adds the project's continuous-integration and release automation — the GitHub Actions
machinery that checks every pull request and turns a merge to `main` into a tagged,
published release. Infrastructure only: no app behavior changes, no new user-facing
surface. This is the plumbing that makes every future release reproducible and verifiable.

### Added

- **PR-check workflow** (`.github/workflows/pr-checks.yml`) — runs the three JVM gates
  (`./gradlew test`, `ktlintCheck`, `lint`) on every pull request, on a Temurin-17
  toolchain with least-privilege permissions. A red gate blocks the merge; this is the
  always-on quality wall in front of `main`.
- **Release workflow** (`.github/workflows/release.yml`) — on a merge to `main` it reads
  `versionName` from `app/build.gradle.kts`, and if no matching tag exists yet, auto-tags
  `vX.Y.Z`, publishes a GitHub Release, and attaches the built **debug APK**. Idempotent:
  a merge whose version is already tagged is a no-op, so re-runs and version-less merges
  never produce a duplicate or corrupt release.
- **Architecture "Release flow" documented** — `docs/architecture.md` now describes the
  CI/release pipeline (previously N/A): what runs on a PR, what runs on a merge, and how a
  version bump becomes a published release.
- **GitHub Actions section in stack-notes** — `docs/stack-notes.md` records the Actions
  idioms used (toolchain setup, least-privilege permissions, env-bound shell interpolation
  for injection safety), so future workflow changes follow the same wired conventions.

### Notes

- **Scope boundary:** releases are **debug-signed only** (no release keystore in CI yet),
  and `connectedAndroidTest` (the instrumented/emulator gate) stays a manual step — it is
  not run in CI in this iteration.
- Backend/infrastructure change — Product Contract intentionally skipped (no user-facing
  behavior). This PR is itself the live validation: `pr-checks.yml` runs on it, and merging
  it triggers `release.yml` to cut `v0.8.0`.

## [0.7.0] — 2026-06-04

Adds the community chat directory — the substrate that lets members of a shared disk
discover which groups exist and are joinable, without a server and without the disk
operator learning the group names or who is in them. The sibling of the user directory:
where that one discovers *people*, this one discovers *groups*. Backend substrate — no
user-facing UI yet; this is the chat-discovery foundation a future "browse community
groups" screen will sit on.

### Added

- **Community chat directory** (`chat-directory`) — members publish a signed, encrypted
  descriptor of a group (its id, title, public/private access) to the shared WebDAV disk;
  everyone in the community can list the directory to discover joinable groups. Each
  descriptor is signed with the publisher's own signing key (so its contents are
  tamper-evident) and encrypted with the community-wide key (so only community members can
  read it — the disk operator sees ciphertext only). Listing decrypts and verifies every
  descriptor, keeps only the latest version per group, and discards anything unsigned,
  tampered, or encrypted to the wrong community.
- **Group-only scope — direct messages are never listed.** The chat directory lists groups
  only. A 1:1 direct-message conversation is refused at publish AND hard-rejected on read,
  so the directory can never leak the community's social graph (who talks to whom). DMs are
  reachable only through their own out-of-band invite, never through discovery. (Scope
  decision: groups in, DMs out — an escalated PM decision.)
- **Private groups: existence and title are discoverable within the community; the content
  key never is.** A private group's existence and title are readable by onboarded community
  members (sealed from the disk operator), but the group's content key is never placed in
  the directory — a member who discovers a private group still needs the key out-of-band to
  read or join it. The directory surfaces existence and metadata only, never a chat key.
- Reuses the existing encryption, identity, transport, and supersede-resolution layers — no
  new dependency. Backend substrate — no UI.

### Documentation

- Authored on-disk protocol §11 (community chat directory layout) in
  `docs/protocol/webdav-layout.md`; §1–§10 unchanged.
- Recorded architecture decision 13 (chat directory substrate: where descriptors live on
  disk, the self-signed advisory-discovery trust model, group-only scope, and no local
  cache this feature) in `docs/architecture.md`; generalized SC18/SC19 to cover both
  community directories and added SC20 (DMs hard-rejected on read).

### Security

- Extended the threat model (`docs/threat-model.md`) with threats T23–T25 covering
  chat-descriptor spoofing / unauthorized supersede under flat trust (signature is
  tamper-evidence and authorship of a version, not chat-ownership authority — an
  authoritative ownership marker is deferred), private-group existence/title visibility
  within the community barrier (accepted design boundary), and chat-directory metadata
  exposure to the disk operator (collection structure/count/timing only; content stays
  sealed).

## [0.6.0] — 2026-06-04

Adds the community user directory — the substrate that lets members of a shared disk
discover each other and each other's verified public keys, without a server and without
the disk operator learning who is in the community. Backend substrate — no user-facing UI
yet; this is the discovery foundation a future contact list will sit on.

### Added

- **Community user directory** (`directory`) — members publish a signed identity entry to
  the shared WebDAV disk; everyone in the community can list the directory and verify each
  member's public keys. Each entry is signed with the member's own signing key (so it
  cannot be forged) and encrypted with a community-wide key (so only community members can
  read it — the disk operator sees ciphertext only). Listing decrypts and verifies every
  entry, keeps only the latest one per member, and discards anything unsigned, tampered, or
  encrypted to the wrong community. Reuses the existing encryption, identity, and transport
  layers — no new dependency. Backend substrate — no UI.

### Documentation

- Authored on-disk protocol §10 (community user directory layout) in
  `docs/protocol/webdav-layout.md`; §1–§9 unchanged.
- Recorded architecture decision 12 (directory substrate: where entries live on disk, the
  self-published signed-entry trust model, and no local cache this feature) in
  `docs/architecture.md`.

### Security

- Extended the threat model (`docs/threat-model.md`) with threats T20–T22 covering
  impersonation-by-display-name (accepted limitation, mitigated by QR safety-number
  verification), community-key compromise, and directory metadata exposure.

### Known limitations

- Trust is self-published: a member's directory entry asserts its own keys. Confirming a
  member is who they claim still requires an out-of-band check (QR safety number).
- Identity name collisions are possible — two members may publish the same display name;
  the public key, not the name, is authoritative.
- The community key is supplied as configuration; secure onboarding/distribution of that
  key is a later feature.

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
