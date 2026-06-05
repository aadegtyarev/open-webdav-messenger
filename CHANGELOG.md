# Changelog

All notable changes to Open WebDAV Messenger are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Pre-1.0: these releases are backend substrates with no end-user UI yet — the public
surface is not stable, and minor versions may change behavior freely.

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
