# Open WebDAV Messenger

## Project

**What:** A simple text messenger for Android that uses public cloud disks (Yandex.Disk, Nextcloud, any WebDAV share) as its only server — no dedicated backend.

**Who:** Privacy-conscious people and small private groups who already have a cloud disk and want to chat without trusting a messenger provider or running their own server.

**Problem solved:** Removes the need for a dedicated chat server or trusting a third-party messenger with your messages — the transport is a file disk you already control, and message content is end-to-end encrypted so the disk operator sees only ciphertext.

**Language canon:** Conversation language: the user's. Artifacts (files, code, commits, agent-authored docs): English.

---

## Architecture

### Tech stack
| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Native Android, first-class on the platform |
| UI | Jetpack Compose | Modern declarative Android UI, full control over the chat surface |
| Background sync | WorkManager | Native, OS-friendly background polling that survives Doze |
| Transport | WebDAV over OkHttp (Sardine) | Public cloud disks expose WebDAV; OkHttp gives full control over polling/retries |
| Crypto | libsodium / Tink (decided by pm-architect) | Audited primitives only — no hand-rolled crypto |
| Database | SQLite (Room) | Local message/key cache, offline-first |

> Stack rationale is owned by `docs/architecture.md` (filled by `pm-architect`). This table is a summary.

### Architectural constraints
Agents must not violate these without an explicit PM decision:

- **The cloud disk is an untrusted transport.** Anything written to WebDAV that carries message content must be encrypted client-side. The disk operator must never see plaintext of private chats.
- **No dedicated backend in the MVP.** All sync, fan-out and ordering happen client-side over WebDAV. (A Telegram gateway via an external server is a deliberately deferred future feature.)
- **WebDAV is slow and rate-limited.** Polling interval must be user-configurable; the protocol must minimise round-trips (batched/append-friendly file layout, no per-message HTTP storms).
- **No hand-rolled crypto.** Only audited libraries and standard primitives.

### Security constraints
- **Message content (private chats): end-to-end encrypted** with an audited AEAD; keys derived from a per-chat passphrase via a memory-hard KDF. The WebDAV server stores ciphertext only.
- **Public chats are explicitly NOT secret** — they use a well-known/shared key so anyone with the link can read them. The UI must clearly warn that a public chat is readable by the disk operator and anyone with access, and nudge toward a password-protected private chat for real conversations.
- **Passphrases / derived keys:** never written to the WebDAV disk, never logged. Stored only in Android's encrypted local storage.
- **Identity keys (X25519) for contact verification:** deferred future enhancement (see architecture decisions).

### Code conventions
AI-specific minimums (adjust in linter config if needed for this project):
- Max file length: 300 lines
- Max function/method length: 50 lines
- Cyclomatic complexity: max 10
- No file-level lint suppressions
- Test coverage: min 80% for new code

Stack linter:
```
./gradlew ktlintCheck
```
(ktlint vs detekt is an open decision — see PM open questions; pipeline assumes ktlint + Android Lint for now.)

---

## Pipeline

Every command in this block must be green before coder is done. No exceptions.

**Tests + lint:**
```
./gradlew test
./gradlew ktlintCheck
./gradlew lint
```

**Validators** (populated by `stack-researcher` at bootstrap and extended on every feature that introduces a new validator — see `docs/stack-notes.md` "Validators wired into pipeline" table):
```
./gradlew lint                  # Android Lint — manifest, API levels, resource issues
./gradlew connectedAndroidTest  # instrumented tests — gates native crypto .so ABI load, Keystore, Room migrations (requires emulator/device)
```

A green tests + lint with a failing validator is still a failed pipeline. Reviewer dim 9 blocks if a validator listed in `stack-notes.md` is not present here, or if it is present here but not actually run by the coder.

---

@.ai-pm/tooling/WORKFLOW.md

---

## UI pattern

Custom UI — the project builds its own chat surface in Jetpack Compose. Conventions live in `docs/ui-guide.md`.

---

## Docs

| File | Purpose |
|---|---|
| `docs/product.md` | **Product front door** — authored PM funnel (Why this exists / What it does today / Documents / Features), owned by `pm-architect`, PM-validated. Not generated |
| `docs/product-map.md` | **Capability map** — contract-centric (group → contract → features + reviews), PM-facing, auto-generated from contracts/plans/reviews |
| `docs/architecture.md` | Stack, decisions, constraints |
| `docs/user-journeys.md` | Existing user scenarios |
| `docs/stack-notes.md` | Stack idioms, constraints, validators, integration contracts (maintained by `pm-stack-researcher`; read by `pm-plan`, `pm-architect`, `pm-coder`, `pm-plan-checker`) |
| `docs/features/` | Past feature plans |
| `docs/ui-guide.md` | UI conventions (custom Compose chat UI) |
| `docs/threat-model.md` | Security model (untrusted WebDAV transport, E2E encryption) |
