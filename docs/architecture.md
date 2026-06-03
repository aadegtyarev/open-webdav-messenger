# Architecture

> Greenfield bootstrap. Filled by `pm-architect` from the PM stack Q&A (bootstrap conversation, 2026-06-03) and `docs/stack-notes.md` (full review 2026-06-03). Component-level idioms, validators and citations are NOT duplicated here — see `docs/stack-notes.md` for each component's canonical docs, rules, and source URLs. This document records the project-level decisions and constraints that sit above those component notes.

## External standards

N/A — solo hobby project, no company or team standards apply. Tech-stack approval, codestyle, and tooling are all decided in this document, not imposed externally.

---

## Tech stack

Source for every row: PM stack Q&A "KEY DECISIONS ALREADY MADE" (bootstrap conversation, 2026-06-03), corroborated by `docs/stack-notes.md`. See `docs/stack-notes.md` for each component's idioms and validators.

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Native Android, first-class on the platform; null-safety + coroutines for off-main-thread I/O and KDF. |
| UI | Jetpack Compose | Declarative UI; full control over a custom chat surface (message list, chat list, settings). |
| Background sync | WorkManager (`PeriodicWorkRequest`) | Native OS-friendly background polling that survives process death; the only documented persistent-work mechanism (subject to the 15-min floor — see decisions). |
| Transport | WebDAV over OkHttp (sardine-android **or** hand-rolled PROPFIND/GET/PUT) | Public cloud disks expose WebDAV; OkHttp gives full control over verbs, conditional requests, and 429 back-off. |
| Crypto (AEAD + KDF) | libsodium via lazysodium-android | Audited primitives; the only option providing both Argon2id (memory-hard KDF) and XChaCha20-Poly1305 AEAD — see decision "Crypto library". |
| Key storage | Android Keystore (direct) | Hardware-backed wrap of derived chat keys; `androidx.security:security-crypto` is deprecated — see Security constraints. |
| Local cache | SQLite via Room | Offline-first message + protocol/key-metadata cache; responsive UI between polls. |
| Compression | `java.util.zip` DEFLATE (starting codec) | Zero-dependency, all API levels, lowest risk surface; compress-then-encrypt — see decision "Compression codec". |
| Markdown render | Hand-rolled `AnnotatedString` parser (starting choice) | Smallest untrusted-input surface over 6 supported elements; full control of link policy — see decision "Markdown rendering". |

## Architectural decisions

Each decision records what was chosen, why, and what was rejected. Decisions 1-5 below were delegated to `pm-architect` by the PM and are decided here. Decisions 6-8 are flagged **OPEN — PM to decide** and recorded so the choice is visible, not pre-empted.

### 1. Crypto library — libsodium-only (no Tink)

**Chosen:** libsodium via lazysodium-android, for both the Argon2id passphrase→key derivation and the XChaCha20-Poly1305 AEAD per message.
**Why:** the product derives a per-chat key from a passphrase, which requires a memory-hard KDF (Argon2id). Tink provides AEAD but has **no** password-based / memory-hard KDF — its maintainers declined built-in support (stack-notes "Crypto library", citing <https://github.com/google/tink/issues/121>). So libsodium must be present regardless of the AEAD choice. Carrying libsodium for the KDF *and* Tink for the AEAD is two native/keyset surfaces for one job; libsodium alone covers both with audited primitives.
**Rejected:** Tink-AEAD + libsodium-KDF split — because it doubles the dependency and key-management surface (a `KeysetHandle` lifecycle on top of libsodium) for no capability libsodium lacks. Tink's Keystore-wrapped keyset management is the only thing it would have added, and the project wraps keys via Android Keystore directly anyway.
**Source:** PM stack Q&A "DECISIONS THAT ARE YOURS TO MAKE NOW" item 1 (bootstrap conversation, 2026-06-03); `docs/stack-notes.md` → Crypto component.
**Validator note:** lazysodium ships native `.so` via JNA per ABI — `./gradlew connectedAndroidTest` is mandatory to catch `UnsatisfiedLinkError` on real ABIs (already in Pipeline; see stack-notes).

### 2. Disk topology — one shared WebDAV credential per chat (Topology A)

**Chosen:** **Topology A — a single shared WebDAV credential (one app-password) per chat, scoped to one shared folder.** Every member of a chat uses the **same** WebDAV URL + app-password; to the provider they are all the **same disk identity**. There is no per-member login, no share invitation, no permission grant — the chat *is* one disk folder reachable by one credential that members hold out-of-band alongside the chat passphrase.
**Why:** a web-research pass found Topology A is the **only** topology that works identically across all target providers (Yandex.Disk, Nextcloud, generic WebDAV) and needs **zero** share-management plumbing — no invitations, permission grants, or public-link/header quirks — sidestepping known fragilities: the Nextcloud NC32 public file-drop regression, the `X-Requested-With` header requirement, ownCloud shared-folder WebDAV bugs, and rclone's lack of per-user scoping. Yandex.Disk in particular cannot be relied on for per-user shared-folder *write*: a shared folder mounts into the invitee's own disk namespace and is *probably* reachable via their own app-password, but the docs are **silent on WebDAV for shared folders (unverified)**, and there is a **quota trap** — a member out of their own free space cannot upload into the shared folder; Yandex public-link upload is **not** a WebDAV feature. The only guaranteed write path on Yandex is therefore one shared app-password.
**Rejected:** **Topology B — per-member own credential against a genuinely-shared folder.** Only Nextcloud supports it cleanly (documented, under `/remote.php/dav/files/{user}`); it is **unverified on Yandex over WebDAV** and **impossible on generic WebDAV** (no native sharing). Kept as a **FUTURE "trusted-server" enhancement for Nextcloud-only deployments**, not the MVP baseline.
**Consequences (load-bearing — underpin decision 3 and the Behavioral/Security contracts):**
- All members share **one disk identity**, so every member can **read AND delete every file** in the shared space. The per-user inbox is therefore a **read-efficiency layout, not an access-control boundary** (see Behavioral contract → Inbox invariants).
- The transport gives **no per-author protection** on any provider; integrity rests on **append-only, content-addressed** message files (a writer only PUTs new files, never overwrites — see Behavioral contract) plus AEAD tamper-detection of content. Deletion resistance is **not** provided (see Security constraints).
- Yandex.Disk WebDAV is **slow** (the server computes file hashes after upload, risking client timeouts), which the fan-out poll/write loop must absorb with retries and exponential back-off (see decision 3 and Architectural constraints).
**Source:** PM decision, bootstrap conversation, 2026-06-03, backed by the WebDAV shared-folder-access research of the same date.

### 3. Aggregated sync — per-user inbox fan-out over WebDAV

**Chosen:** an on-disk protocol where one poll cycle returns everything changed for a user. Senders **fan out** by appending each outgoing message into every recipient's per-user **inbox** on the disk; a client polls its single inbox (one `PROPFIND Depth: 1` + conditional `GET`s) rather than polling each chat folder directly. Optimistic concurrency uses **ETag + `If-Match` conditional requests** (RFC 4918 §8.6/§10.4), **not** WebDAV `LOCK`. The on-disk layout is append-friendly so a writer adds an entry without rewriting prior content.
**Why:** WebDAV is slow and Yandex.Disk returns `429 Too Many Requests` under frequent `PROPFIND` (stack-notes → OkHttp/WebDAV gotchas). Polling each chat per cycle is an HTTP storm that hits the rate limit; a single per-user inbox collapses the poll to minimal round-trips. ETag conditional writes are portable across providers, whereas `LOCK` is optional/advisory and varies by provider (stack-notes → WebDAV constraints). This fan-out sits on top of the single shared credential fixed in **decision 2 (Disk topology)**: all members share one disk identity, so the inbox is a read-efficiency layout, not an isolation boundary. The loop must additionally budget retries and **exponential back-off** for Yandex.Disk's slow post-upload hash computation (which can time out the client), complementing the 429 back-off above.
**Rejected:** (a) **per-chat polling** — N round-trips per cycle, directly triggers 429. (b) **WebDAV `LOCK` for write coordination** — not portable; Yandex/Nextcloud differ on support; ETag `If-Match` gives lost-update protection without it.
**Forward pointer:** the byte-level on-disk spec (folder/file naming, envelope bytes, append semantics, fan-out write order) will be authored as **`docs/protocol/webdav-layout.md`** during the first transport feature. It does **not** exist yet and is **not** written here. The format **invariants** that any such spec must honor are pinned in *Behavioral contract* below. `pm-plan-checker` should block any transport feature whose plan does not reference `docs/protocol/webdav-layout.md` (stack-notes → Integration contracts note).
**Source:** PM stack Q&A "DECISIONS THAT ARE YOURS TO MAKE NOW" item 2 (bootstrap conversation, 2026-06-03); `docs/stack-notes.md` → OkHttp/WebDAV component + Integration contracts table.

### 4. Compression codec — DEFLATE (`java.util.zip`), compress-then-encrypt, per-message

**Chosen:** start with stdlib DEFLATE via `java.util.zip` (raw `Deflater`/`Inflater`). Compress the message body **before** AEAD encryption. Compress **each message independently** (never co-compress two messages or a secret with attacker-influenced data in one context). Bound decompressed size (zip-bomb guard). Record the codec id in the message envelope so the reader knows whether/how to inflate, including a `none` value.
**Why:** AEAD ciphertext is high-entropy and incompressible, so compress-then-encrypt is the only order that saves bytes (stack-notes → compression "Ordering invariant"). DEFLATE is zero-dependency, available on all supported API levels, and carries the lowest risk surface — no native `.so`, no per-ABI packaging, no `sun.misc.Unsafe`. The codec id in the envelope makes the codec swappable later (e.g. to zstd) without breaking older readers.
**Rejected:** (a) **zstd-jni** — best ratio but bundles a native `.so` per ABI (`UnsatisfiedLinkError` risk + larger APK), same packaging hazard as lazysodium; not worth the surface for an MVP text payload. (b) **aircompressor (v2 line)** — pure-Java zstd/lz4 but relies on `sun.misc.Unsafe` and carries decompressor-CVE history (CVE-2024-36114); decompressing untrusted disk content with an `Unsafe`-backed inflater is exactly the surface to avoid. (c) **encrypt-then-compress** — saves nothing; ciphertext does not compress.
**Security tie-in:** the CRIME/BREACH-class leak (co-compressing secret + attacker-influenced data leaks length) is the reason for per-message independent compression — see Security constraints.
**Source:** PM stack Q&A "DECISIONS THAT ARE YOURS TO MAKE NOW" item 3 (bootstrap conversation, 2026-06-03); `docs/stack-notes.md` → Traffic compression component.

### 5. Markdown rendering — hand-rolled `AnnotatedString` parser

**Chosen:** a hand-rolled Compose `AnnotatedString` parser covering exactly the 6 supported constructs (bold, italic, inline code, code block, blockquote, link), treating everything else — including `<...>` — as literal text. No HTML passthrough, no remote image loading, link targets restricted to a scheme allowlist with the URL visible, navigation only on explicit user tap.
**Why:** rendered content is untrusted decrypted text from a remote party, so the decisive axis is attack surface, not feature richness. A hand-rolled parser for 6 elements is the smallest surface, has no transitive markdown engine, and gives full control of link policy. The MVP subset is small enough to own and unit-test.
**Rejected:** (a) **jeziellago/compose-markdown** — hard-wires `HtmlPlugin` (raw HTML rendered), unconditional Coil image loading (outbound fetch on message open = tracking/SSRF-ish "message opened" leak), and `LinkifyPlugin` auto-linking, **with no public off-toggle**; neutralizing these means forking/custom Markwon config — more work and more surface than owning a 6-element parser. (b) **mikepenz/multiplatform-markdown-renderer** — smaller default surface (remote images opt-in, links via Compose `UriHandler`); viable, and the preferred library fallback **if** the hand-rolled parser proves too costly to maintain — but still a full markdown engine where 6 constructs are needed.
**Note:** Markdown rendering adds **no** integration contract — it is local UI over already-received text (stack-notes → Integration contracts note).
**Source:** PM stack Q&A "DECISIONS THAT ARE YOURS TO MAKE NOW" item 4 (bootstrap conversation, 2026-06-03); `docs/stack-notes.md` → Markdown rendering component.

### 6. OPEN — Polling cadence: WorkManager floor vs foreground service

**Status:** OPEN — PM to decide.
**Chosen:** (not decided) — recorded so the trade-off is explicit.
**The choice:** WorkManager `PeriodicWorkRequest` is silently clamped to a **15-minute minimum**, and Doze / App-Standby Buckets defer it further (down to once-per-day in the Restricted bucket). The user-configurable polling interval is therefore *aspirational below ~15 min* on the WorkManager path. Sub-15-minute delivery requires a user-visible **foreground service** (permanent notification) or re-enqueued one-time work — the only documented escapes from the floor.
- **Option A — WorkManager only:** no permanent notification, OS-friendly, but interval clamps to ≥15 min and degrades under Doze/buckets.
- **Option B — Foreground-service mode:** tighter cadence at the cost of a persistent notification and battery; needs an Android 14+ declared FGS type.
**Recommendation to PM:** offer both — default to the WorkManager floor, expose an opt-in foreground-service "faster delivery" toggle that makes the notification cost explicit. Real-time/sub-15-min push is already listed out of scope for MVP (product.md).
**Source:** PM stack Q&A "PRODUCT DECISIONS TO FLAG" (bootstrap conversation, 2026-06-03); `docs/stack-notes.md` → WorkManager + Android platform components.

### 7. OPEN — Static-analysis stack: ktlint vs detekt vs both

**Status:** OPEN — PM to decide.
**Default in place:** the Pipeline currently assumes **ktlint + Android Lint** (`./gradlew ktlintCheck` + `./gradlew lint`). detekt (`./gradlew detekt`, smell-focused) is the alternative/complement. If the PM adds or swaps in detekt, the Pipeline block in `CLAUDE.md` and the stack-notes validators table must be updated together.
**Source:** PM stack Q&A "PRODUCT DECISIONS TO FLAG"; `docs/stack-notes.md` → Gradle component.

### 8. OPEN — CI must provide an Android emulator/device for `connectedAndroidTest`

**Status:** OPEN — PM to decide (infrastructure).
**Why it matters:** native-crypto (lazysodium `.so` per ABI) and Android Keystore wrap/unwrap and Room migrations are only verifiable instrumented, on a device/emulator. Without an emulator in CI, `./gradlew connectedAndroidTest` cannot run and these paths go unverified — a green tests+lint with no instrumented run is a false-green for the most security-critical code.
**Source:** PM stack Q&A "PRODUCT DECISIONS TO FLAG"; `docs/stack-notes.md` → Validators table.

## Architectural constraints

Hard boundaries. Agents must not violate these without an explicit PM decision. Source for each: PM stack Q&A + `CLAUDE.md` Architectural constraints (bootstrap conversation, 2026-06-03).

- **The cloud disk is an untrusted transport.** Anything written to WebDAV that carries private-chat content must be AEAD-encrypted client-side. The disk operator must never see plaintext of a private chat. (Persistence-boundary rule, stack-notes → Platform filesystem layout.)
- **One shared WebDAV credential per chat (Topology A).** Every member of a chat uses the same WebDAV URL + app-password, so all members are one disk identity and can read *and* delete every file in the shared folder. The protocol must not assume any provider-enforced per-author access control; message files are append-only and content-addressed (never overwritten), and deletion resistance is out of scope for the MVP (decision 2; Security constraints). Per-member credentials (Topology B) are a future Nextcloud-only enhancement, not a current assumption.
- **Keys and plaintext stay device-local.** Only AEAD ciphertext and non-secret protocol files cross to the disk. Never write a passphrase, a derived key, or private plaintext to the WebDAV disk, and never log them.
- **No dedicated backend in the MVP.** All sync, fan-out, and ordering happen client-side over WebDAV. (A Telegram gateway via an external server is a deliberately deferred future feature.)
- **WebDAV is slow and rate-limited.** The protocol must minimise round-trips: per-user inbox fan-out, append-friendly layout, ETag conditional writes, 429 back-off. No per-message HTTP storms, no reliance on `PROPFIND Depth: infinity`, no reliance on WebDAV `LOCK`. Yandex.Disk is additionally slow because it computes file hashes *after* upload (risking client timeouts), so every write must tolerate retries with exponential back-off (decision 2 / decision 3).
- **No hand-rolled crypto.** Only audited libraries and standard primitives (libsodium). This does **not** extend to the markdown parser or the on-disk envelope framing, which are app-owned and explicitly *not* cryptographic.
- **Compress-then-encrypt, per message.** Never co-compress a secret with attacker-influenced data in one compression context (CRIME/BREACH class). Always bound decompressed size.
- **Untrusted decrypted content is never auto-actioned.** No HTML passthrough, no remote image auto-loading, no link auto-navigation — see Security constraints.
- **All blocking work off the main thread.** Network, disk, Room, and the Argon2id KDF run on background dispatchers; the KDF in particular is memory-hard/slow and must be isolated (stack-notes → Kotlin/Room/Compose).

## File layout (module map)

> **Greenfield — no source tree exists yet.** `ls` shows no `app/`/`src/` and `git ls-tree -r --name-only HEAD` is empty (no commits). The table below is the **planned** module map (the intended package structure under a single-module Android `app/`), not an observed tree. It is a forward target for the first features and must be reconciled against the real tree once code lands — any divergence then is a finding to fix here. Marked rows are intentions, not facts.

| Directory / module (planned) | Responsibility |
|---|---|
| `app/` | Single Android application module (Gradle), all source under it. |
| `app/.../ui/` | Jetpack Compose chat surface — chat list, message list, composer, settings (incl. polling-interval control). State-hoisted; no I/O in composables. |
| `app/.../crypto/` | libsodium wrappers — Argon2id passphrase→key derivation, XChaCha20-Poly1305 AEAD seal/open. No keys leave this layer in extractable form. |
| `app/.../keystore/` | Android Keystore wrap/unwrap of derived chat keys; device-local only. |
| `app/.../transport/` | OkHttp + WebDAV layer — PROPFIND/MKCOL/GET/PUT/DELETE, ETag/`If-Match` conditional writes, 429 back-off. |
| `app/.../protocol/` | On-disk protocol model — inbox fan-out, message envelope encode/decode, codec id, ordering. Implements the (forthcoming) `docs/protocol/webdav-layout.md` spec. |
| `app/.../codec/` | Compression — DEFLATE compress/inflate, bounded decompression, per-message. |
| `app/.../markdown/` | Hand-rolled `AnnotatedString` parser for the 6-element subset + link scheme allowlist. |
| `app/.../data/` | Room cache (entities, DAOs, migrations, schema export) for messages and protocol/key metadata. |
| `app/.../sync/` | WorkManager worker(s) — one-poll-cycle sync over the inbox; optional foreground-service mode (pending decision 5). |
| `docs/` | Project docs (this file, stack-notes, product funnel, threat model, user journeys; `docs/protocol/webdav-layout.md` forthcoming). |
| `.ai-pm/` | AI-PM tooling, contracts, arch notes. |

## Integration contract

This is an Android app installed from an APK, not a library or service with downstream code consumers. There is no exported API, no `pip install`-style entry point, no env contract for callers.

- **Build / install (aligned with `README.md`):** built with Gradle (`./gradlew assembleDebug`); the deliverable is an Android APK installed on a device. No install-time external integration surface.
- **Runtime integration — WebDAV disk:** the app integrates with a user-supplied WebDAV share (Yandex.Disk app-password/OAuth, or Nextcloud at `/remote.php/dav/files/{user}/...`) entirely at runtime via PROPFIND/MKCOL/PUT/GET/DELETE over OkHttp. The load-bearing contract for *interoperability between app instances* is the on-disk protocol layout — **`docs/protocol/webdav-layout.md`** (not yet authored; see decision 2 and Behavioral contract). Until that exists, treat the invariants below as the contract.
- **Runtime integration — Android Keystore:** keys generated on-device via `KeyGenParameterSpec`; no separate artifact file. (stack-notes → Integration contracts table.)

## Behavioral contract (taxonomies & invariants)

The single home for this project's domain taxonomies and format invariants. The forthcoming `docs/protocol/webdav-layout.md` will specify byte-level layout; it must honor — and `docs/user-journeys.md` and contracts must reference, not restate — the invariants below. Marked `[?]` items are skeleton placeholders to be pinned by the first transport feature; they are listed so the protocol spec inherits a fixed taxonomy rather than inventing one.

### Chat-type enum

| Value | Meaning | Secrecy |
|---|---|---|
| `private` | Symmetric AEAD; key = Argon2id(passphrase, salt). Passphrase shared out-of-band. Groups (MVP): one shared per-chat passphrase among members (no per-member key wrapping yet). | Secret — disk sees ciphertext only. |
| `public` | Uses a well-known / shared key so anyone with the link can read. | **NOT secret** — readable by the disk operator and anyone with access. UI must warn and nudge to a private chat. |

This enum is closed at **2** values for the MVP. Adding a value is an architectural decision.

### Reaction enum

Exactly **5 fixed reactions** (MVP). The concrete glyph/identifier set is `[?]` — to be fixed by the reactions feature and recorded here as a closed enum; any envelope reaction field must validate against it.

### Message envelope (conceptual fields — byte layout TBD in `webdav-layout.md`)

Every message written to a WebDAV inbox is an AEAD ciphertext whose plaintext, once opened, is an envelope carrying at least:

- **message-id** — grammar `[?]` (stable, unique per message; ordering/dedup key; never reused). The on-disk file name derives from this id / a content hash, so each message is **content-addressed and append-only** — a writer PUTs a new file and never overwrites a prior one (decision 2; Inbox invariants below).
- **chat-id** — grammar `[?]` (identifies the chat; inbox/folder naming derives from it).
- **chat-type** — one of `{private, public}` (enum above).
- **sender** — author identity (no X25519 identity keys in MVP; this is a display/address identifier, not a verified key).
- **reply-to** — optional message-id this message quotes (replies feature).
- **codec id** — which codec compressed the body: at least `{none, deflate}` (decision 3). Reader inflates per this field; unknown codec = error path, not a guess.
- **reaction** — optional, one of the 5-reaction enum (when the envelope is a reaction event).
- **body** — the (optionally compressed, then encrypted) message text + supported Markdown subset.
- **timestamp / ordering token** — `[?]` (see ordering rules).

### Inbox / file-naming invariants

- **Per-user inbox fan-out:** a sender writes a copy of each outgoing message into **every recipient's** inbox. A client reads only **its own** inbox per poll cycle. Inbox path/file naming derives from the recipient + chat-id; concrete grammar `[?]`.
- **The inbox is a read-efficiency layout, NOT an access-control boundary.** Under the single shared WebDAV credential (decision 2, Topology A) every member is the same disk identity, so every member can read **and delete** every file in the shared space — including other members' inboxes. The fan-out exists only to make a poll cycle one cheap `PROPFIND Depth: 1` per client, not to isolate members from each other.
- **Append-only, content-addressed message files:** a message file name derives from its message-id / content hash, and a writer only ever **PUTs a new file — never modifies or overwrites an existing one**. This append-only, content-addressed discipline is the integrity compensation for the flat trust model: the transport gives no per-author protection on any provider (decision 2), so files are never mutated in place.
- **Conditional write:** every write that risks a lost update uses ETag + `If-Match`; a competing-writer 412 is a normal retry path, not a crash.

### Ordering & causality

- Clients must tolerate out-of-order arrival and duplicate delivery (fan-out + polling can re-deliver); **message-id is the dedup key**.
- A reply (`reply-to`) may arrive before the message it quotes; the UI must degrade gracefully (show as reply to an unknown/unloaded message), not error.
- Exact ordering token and tie-break rule are `[?]` — to be pinned in `webdav-layout.md`; until then, treat ordering as best-effort over a monotonic per-sender token.

### Decompression bound

- Decompressed plaintext size is bounded (zip-bomb defense); exceeding the bound is an error path. The numeric bound is `[?]` — to be fixed alongside the codec in `webdav-layout.md`.

## Release flow

N/A — no automated release pipeline exists. The repository has no commits yet, `.github/workflows/` is absent, and there is no `auto-tag.yml` or equivalent CI. The deliverable is a Gradle-built APK (`./gradlew assembleDebug`). When release automation is introduced, this section and CI must be authored together. (Decision 7 flags the related need for a CI emulator before `connectedAndroidTest` can gate releases.)

## Security constraints

Source: PM stack Q&A + `CLAUDE.md` Security constraints + `docs/stack-notes.md` security gotchas (bootstrap conversation, 2026-06-03).

- **Private-chat content:** end-to-end encrypted with XChaCha20-Poly1305 AEAD; per-chat key derived from a passphrase via Argon2id (memory-hard). The disk stores ciphertext only.
- **Public chats are explicitly NOT secret:** shared/well-known key. The UI must clearly warn that the chat is readable by the disk operator and anyone with access, and nudge toward a password-protected private chat for real conversations.
- **Passphrases and derived keys:** never written to the WebDAV disk, never logged. Stored only device-local, wrapped via **Android Keystore directly** — `androidx.security:security-crypto` (EncryptedSharedPreferences) is **deprecated (1.1.0, 2025)** and must not be used for new storage.
- **Compression side channel:** compress each message independently; never co-compress secret + attacker-influenced data in one context (CRIME/BREACH class). Length-hiding/padding to be considered in `webdav-layout.md`.
- **Untrusted decompression:** bound decompressed size (zip-bomb guard); decompression failure is a normal error path, not a crash.
- **Untrusted Markdown:** no raw-HTML passthrough, no remote image auto-loading, link scheme allowlist (e.g. `https`/`http`/`mailto`) with the real URL visible, navigation only on explicit tap, no autolinkification.
- **No hand-rolled crypto:** audited primitives only.
- **Java-interop nullability:** crypto/HTTP libraries are Java; values crossing the boundary are platform types and must be treated as nullable — no `!!` on WebDAV/crypto paths.
- **Flat trust model (shared disk credential, decision 2):** all members of a chat share one WebDAV credential, so **any member can technically delete or overwrite another member's message files**. This is an accepted, deliberate MVP limitation — all members already share the chat passphrase, so trust is "knows the passphrase = insider". AEAD provides tamper-detection of message **content**, but **not** deletion resistance. Protection against deletion (signatures, replicated/witnessed logs) is future work. Append-only, content-addressed files (Behavioral contract) limit silent in-place mutation but do not stop deletion.
- **Identity keys (X25519) for contact verification:** deferred (out of MVP scope) — message authenticity rests on AEAD + the shared passphrase only; no cryptographic sender verification in v1.

## Code conventions

### AI-specific minimums

- Max source file: 300 lines
- Max function / method: 50 lines
- Cyclomatic complexity: max 10 per function
- No file-level lint suppressions (only line- or function-level with a comment)
- New code test coverage: min 80%

(Mirrors `CLAUDE.md` Code conventions. Source: bootstrap conversation, 2026-06-03.)

### Stack-specific rules

- **Null safety:** prefer `?.` / `?:` over `!!`; treat every `!!` on a WebDAV or crypto path as a defect candidate. Java-interop values are nullable.
- **Off-main-thread:** no network, disk, Room, or KDF work on the UI dispatcher; use coroutines on `Dispatchers.IO`. Isolate the Argon2id KDF so it does not starve other CPU-bound work.
- **Compose:** composables are side-effect-free and order/parallel-safe; mutate state in event callbacks / `LaunchedEffect`, not in the composable body; hoist state into ViewModels.
- **OkHttp:** one shared `OkHttpClient`; always close response bodies.
- **Room:** no main-thread DB access; `suspend` DAOs / `Flow`; `exportSchema = true` with checked-in schema JSON; a version bump needs a `Migration`.
- (Full idioms with source URLs live in `docs/stack-notes.md` — not duplicated here.)

### Linter commands

```
./gradlew ktlintCheck   # Kotlin style (or ./gradlew detekt — open decision 6)
./gradlew lint          # Android Lint (aborts on error by default)
```

## Dependencies

### Policy

Prefer the Android/Kotlin standard library and Jetpack. Add a third-party dependency only when it provides an audited capability the stdlib lacks (crypto) or saves substantial non-trivial code, and is actively maintained. **Treat any dependency that bundles a native `.so` per ABI as a packaging risk** (`UnsatisfiedLinkError`) requiring instrumented verification. No GPL constraint imposed; license each addition consciously (project license itself is TBD — see README).

### Current dependencies

> No build files exist yet (greenfield). The table records the **planned** core dependencies implied by the stack decisions, to be reconciled with `app/build.gradle.kts` when it lands.

| Package | Purpose | Added |
|---|---|---|
| Kotlin stdlib + coroutines | Language + off-main-thread concurrency | bootstrap (planned) |
| Jetpack Compose | UI toolkit | bootstrap (planned) |
| androidx.work (WorkManager) | Periodic background polling | bootstrap (planned) |
| OkHttp (+ sardine-android, optional) | WebDAV transport | bootstrap (planned) |
| com.goterl:lazysodium-android + net.java.dev.jna:jna | Argon2id KDF + XChaCha20-Poly1305 AEAD (native `.so`) | bootstrap (planned) |
| androidx.room | Local offline-first cache | bootstrap (planned) |
| `java.util.zip` (stdlib) | DEFLATE compression — zero dependency | bootstrap (planned) |
| (markdown) | Hand-rolled `AnnotatedString` parser — no dependency | bootstrap (planned) |
