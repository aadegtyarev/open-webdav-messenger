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
**On-disk spec (authored):** the byte/path-level layout — folder/inbox layout, content-addressed file naming, the message-id grammar, the append-only rule, the ordering token, and the envelope framing (with the compression `codec-id` and the opaque ciphertext slot) — is specified in **`docs/protocol/webdav-layout.md`** (authored 2026-06-03 by the first transport feature). That file is the **authoritative source** for this fan-out's on-disk format; the *Behavioral contract* below states the invariants and points there (move-not-copy). `pm-plan-checker` blocks any transport feature whose plan does not reference `docs/protocol/webdav-layout.md` (stack-notes → Integration contracts note).
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

### 9. Crypto substrate — key sources, AEAD, key storage

**Chosen:** the E2E-encryption substrate (`docs/features/crypto_plan.md`, lands the `app/.../crypto/` + `app/.../keystore/` modules) provides three per-chat key sources feeding **one** AEAD construction, with keys stored device-local and Keystore-wrapped.

- **AEAD — single layer, XChaCha20-Poly1305** (libsodium combined mode, decision 1). Every message is sealed with **one** AEAD pass regardless of key source. The on-disk blob layout — `nonce(24) ‖ AEAD-ciphertext-with-tag`, 24-byte random nonce at the front, the 8-byte envelope header bound as AAD — is pinned in **`docs/protocol/webdav-layout.md` §5.1** (authored as part of this feature).
- **Three key sources** (the substrate exposes all three; the chat taxonomy below maps a chat's config onto one):
  - **known** = `KDF(in-app constant ‖ chat-id)` → the **public**-chat key. The in-app constant is **non-secret and lives in code** — there is **no build-secret injection**, because the public-chat key is by design not a secret (anyone with the app + chat-id can derive it). (The build-secret pattern is reserved for a *genuine* secret, e.g. a future Telegram-gateway token.)
  - **random** = a CSPRNG-generated symmetric key → a **private** chat **without** a password; the key is distributed **out-of-band via a future invite** (string + QR) feature. The substrate can import/export the raw key for that feature; it does not encode invites itself.
  - **passphrase** = `Argon2id(passphrase, salt)` → a **private** chat **with** a password. Uses the libsodium Argon2id **INTERACTIVE** preset (64 MiB) so it survives low-RAM devices (stack-notes → Crypto/Kotlin: run off the UI thread). The **salt is derived deterministically from the chat-id** so members independently derive an identical key from the same passphrase. (Note: a future **roster** feature may move to a **stored random salt** per chat; the deterministic-from-chat-id salt is the MVP choice, not a permanent constraint.)
- **Key storage:** chat keys are stored **device-local, wrapped via Android Keystore directly** (not `androidx.security:security-crypto`, which is deprecated — Security constraints). **Raw keys and passphrases are never written to the WebDAV disk and never logged.**

**Rejected — double-layer encryption (app-key under passphrase):** sealing private content first under the in-app app-key and then under the passphrase key adds **no** security, because the app key is **extractable from the APK**. A second layer keyed on an extractable constant is **obscurity, not security**, for double the crypto surface. Single-layer AEAD with the passphrase-derived (or random) key is the honest boundary. (Also recorded in `docs/features/crypto_plan.md` → Out of scope.)

**Security rationale — the disk app-password is NEVER a content key (recorded explicitly):** the disk operator (e.g. Yandex) **receives the WebDAV app-password** to authenticate every request. If the content key were derived from that password, the operator would hold the means to **decrypt all content**. Therefore content keys (known / random / passphrase) are **always independent of disk access** and are **never written to the disk**. This is the load-bearing reason none of the three key sources touch the disk credential.

**Planned follow-ons (noted so the substrate seam is understood — NOT designed here):**
- **(a) X25519 key agreement — a fourth key source. → IMPLEMENTED / IN PROGRESS as the identity substrate (decision 10).** Enables **remote private chats between members already sharing a disk**: members publish public keys in a directory (b), do a Diffie-Hellman exchange, and feed the resulting (KDF'd) shared secret as a symmetric key into **this same** XChaCha20-Poly1305 AEAD. The X25519 key-agreement primitive, the DH→KDF→ChatKey path, sealed-box, signing, and the verification fingerprint now land in the `app/.../identity/` module — see **decision 10 (Identity substrate)**. (Group per-member key wrapping is where X25519 later helps — `crypto_plan.md` → Out of scope.)
- **(b) A user/chat directory on the disk** for discovery — **still future.** **Known caveat:** a directory exposes **who-is-in-which-chat metadata to the disk operator** until it is itself encrypted with a community key — a later confidentiality concern, not solved here. (The directory feature also defines the on-disk format for published, signed identity entries in `docs/protocol/webdav-layout.md` — decision 10.)
- **(c) Invite / onboarding** as a **self-contained string + QR** (no server/URL — **there is no server**, decision 2 / Architectural constraints) carrying **disk access + chat-id + (for random-key chats) the key** — **still future.** The substrate's raw-key import/export exists to feed this.
- **Key rotation** (re-keying a chat, e.g. on membership change) — **still future.** Uses the identity substrate's sealed-box primitive (decision 10) to encrypt the new key per remaining member; because sealed-box is sender-unauthenticated, rotation payloads MUST also be Ed25519-signed (decision 10).
- **Forward secrecy / double-ratchet** remains the **deferred, genuinely-stronger** private-chat path — **still future**; a separate larger feature, not on this substrate's critical path.

**Source:** PM decision, bootstrap+crypto planning conversation, 2026-06-03; `docs/features/crypto_plan.md` (approved plan); `docs/stack-notes.md` → Crypto library + Android Keystore components.

### 10. Identity substrate — per-user X25519 + Ed25519 keypairs

**Chosen:** the asymmetric / identity substrate (`docs/features/identity_plan.md`, lands the `app/.../identity/` module) gives each user a stable on-device **identity** and the public-key primitives the directory / private-chat / rotation features consume. It is **pure crypto** — it produces and consumes opaque bytes and key handles, defines **no** on-disk format, and publishes nothing. It is the realisation of decision 9's planned "fourth key source" follow-on (a).

- **Two distinct keypairs per identity — one identity, one fingerprint.** A user's identity is an **Ed25519 signing** keypair AND a **separate X25519 box** keypair, each generated independently on-device. This is chosen over the single-Ed25519-converted-to-X25519 path because libsodium's own guidance is that "using distinct keys for signing and for encryption is still highly recommended" (stack-notes → Crypto "Public-key primitives", <https://doc.libsodium.org/advanced/ed25519-curve25519>). The single-identity **UX is preserved despite two keys** because the **verification fingerprint is computed over BOTH public keys**, so the user still verifies exactly **one** fingerprint.
- **DH → KDF → ChatKey — the fourth key source for the existing AEAD.** Given my X25519 secret key and a peer's X25519 public key, `crypto_box_beforenm` yields a 32-byte shared secret; that secret is run through a **KDF (BLAKE2b / `crypto_kdf`)** to produce the symmetric `ChatKey`. The **raw shared secret is NEVER used directly as the AEAD key** — it is a DH output, not a uniformly-distributed key (stack-notes, <https://doc.libsodium.org/public-key_cryptography/authenticated_encryption>). The derived `ChatKey` is a drop-in **fourth** source feeding the existing single-layer XChaCha20-Poly1305 AEAD (decision 9), alongside passphrase / random / known. Symmetric: A(myPriv, B.pub) == B(myPriv, A.pub), so both devices derive the same key.
- **Sealed-box is the per-recipient encryption primitive — and is sender-unauthenticated (load-bearing).** `crypto_box_seal` (lazysodium `cryptoBoxSealEasy` / `cryptoBoxSealOpenEasy`) encrypts a payload to a recipient's X25519 public key only, using an ephemeral internal sender keypair; only the recipient's keypair opens it. The future **rotation** feature uses this to encrypt the new disk password per remaining member. **Property the consumers MUST respect:** sealed-box proves only that *the holder of the recipient secret key could open the payload* — it does **NOT** authenticate the sender (stack-notes, <https://doc.libsodium.org/public-key_cryptography/sealed_boxes>). Therefore the rotation feature MUST add an **Ed25519 detached signature** over rotation payloads; sealed-box alone never establishes who rotated the key.
- **Signatures — Ed25519 detached, hard reject on failure.** `crypto_sign_detached` / `crypto_sign_verify_detached`; verification failure (libsodium `-1`) is a **hard reject**, never best-effort. This is the directory-entry-authentication primitive (and the rotation-payload signature above).
- **Fingerprint — symmetric, over both parties' public identity keys.** BLAKE2b (`crypto_generichash`) over the two parties' public identity keys, **sorted then concatenated** — the sort is what makes the value identical on both devices regardless of which computes it (Signal safety-number pattern; stack-notes, <https://doc.libsodium.org/hashing/generic_hashing>, <https://signal.org/blog/safety-number-updates/>). This substrate yields the deterministic fingerprint **bytes**; the human display format (digit count / grouping) is **deferred to the UI feature**.
- **Key storage.** The identity **secret** keys (Ed25519 `sk`, X25519 `sk`) are **Android Keystore-wrapped, device-local, never written to the WebDAV disk** — the same discipline as chat keys (decision 9; Security constraints), under a distinct alias/store so they do not disturb stored chat keys. The identity **public** keys are publishable (that is the point of a future directory entry).

**No on-disk format here.** This substrate defines no WebDAV layout. The on-disk format for **published identity entries** (signed public keys) and for **sealed rotation payloads** is specified later in `docs/protocol/webdav-layout.md` by the **directory** and **rotation** features respectively, not by this feature.

**Source:** PM + design decisions, 2026-06-03; `docs/features/identity_plan.md` (approved plan); `docs/stack-notes.md` → Crypto library "Public-key primitives (X25519 identity substrate)".

### 11. Message model — signed plaintext inside the envelope

**Chosen:** the message-model substrate (`docs/features/message-model_plan.md`, lands the `app/.../message/` module) defines the **structured plaintext that lives inside the §5.1 AEAD ciphertext-blob** — the bytes the existing crypto substrate (decision 9) seals and opens. It is the inner message format, distinct from the outer envelope framing (decision 3 / `docs/protocol/webdav-layout.md` §5). The authoritative byte layout is **`docs/protocol/webdav-layout.md` §8 (Message plaintext format)**, authored as part of this feature.

- **Per-message Ed25519 signature — intra-chat sender authentication.** Every message is signed with the **sender's identity Ed25519 secret key** (decision 10) and carries the matching **public** key plus a 64-byte detached signature over the serialized fields (excluding the signature itself). The shared AEAD key (decision 9) proves only that *some* chat member sealed the message — it cannot distinguish *which* member, so a member could otherwise forge a message as another. The signature closes that gap: a member cannot sign with another member's Ed25519 secret, so impersonation is detected on `verify_detached` (libsodium `-1` = hard reject, never best-effort). The whole signed message rides inside the ciphertext-blob, so the sender public key and signature are also confidentiality-protected. (Binding a key to a human is the future directory/safety-number feature, decision 10; this feature gives the verifiable signature so it is checkable once the directory exists, plus tamper-evidence + intra-chat non-repudiation now.)
- **Reaction = a first-class message kind**, not a field on a text message: `kind = 0x02`, carrying a `target-id` (the reacted-to message's §2 file name) and a `reaction-index` in the **closed range 0..4** (the fixed 5-reaction set — Behavioral contract → Reaction enum; the concrete glyphs stay a UI concern, not fixed here). An index ∉ 0..4 is rejected on parse.
- **Reply = an optional field** (`reply-to`, the quoted message's §2 file name) on a `text` message — not a separate kind. Absent = not a reply; a `reply-to`/`target-id` may reference an as-yet-undelivered message (§4 causality) — that is a sync/UI graceful-degradation concern, not a parse error.
- **Versioned, extensible, reject-don't-guess encoding.** A 1-byte `msg-format-version` + 1-byte `kind` tag + a TLV field body (per webdav-layout §8). The `kind` enum is **closed for now** to `{text (0x01), reaction (0x02)}`; `edit`/`delete`/`system` are **reserved, not defined now**. An unknown version, unknown kind, malformed/truncated buffer, bad length prefix, out-of-range enum, or a signature that does not verify is a **typed rejection** (dropped, never partially parsed, never a crash) — consistent with the §7 / §8 reject-don't-guess discipline and the `webdav-transport`/crypto reader rules.
- **No inner self-id — a message's id is its §2 file name.** A message carries **no** inner field naming itself; its identity **is** the content-addressed §2 file name of the envelope that seals it, assigned at seal time. (An inner field forced to equal that name would be a fixed point over `SHA-256` of the *sealed* bytes — which encrypt the field — i.e. unsatisfiable without inverting the hash.) **References carry other messages' §2 file names:** `reply-to` (text) and `target-id` (reaction) each hold the **full §2 file name** of an already-received message, so a reference resolves directly to an on-disk path (one id space, no mapping table); a reference to a not-yet-received message is a valid string that degrades gracefully (§4 causality), not a parse error.
- **Ordering** uses the outer §4 `order-token`; the inner `send-timestamp` (text only) is sender wall-clock, **display-only / best-effort**, never trusted and never used for ordering.

**Scope:** pure message format + serialize + sign/verify + crypto integration. It does **not** touch the on-disk layout, polling, sync, or local history (the next `sync` feature), no UI (rendering the Markdown / the 5 reaction glyphs / reply display is the UI feature), and no compression (`codec-id` stays `0x00`).

**Source:** PM + design decisions, 2026-06-03; `docs/features/message-model_plan.md` (approved plan); `docs/protocol/webdav-layout.md` §8 (authoritative byte layout, authored this feature); decision 9 (AEAD substrate the plaintext rides inside) + decision 10 (Ed25519 identity the signature uses); `docs/stack-notes.md` → Crypto "Public-key primitives" (Ed25519 detached sign/verify, -1 = hard reject). **Corrected 2026-06-04** (coder escalation): the originally-specified inner `message-id` field equal to the §2 file name was an unsatisfiable fixed point over SHA-256 of the sealed bytes; removed — identity is now the §2 file name alone, and `reply-to`/`target-id` carry other messages' §2 file names (`docs/protocol/webdav-layout.md` §8.4/§8.5/§8.6).

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

> **Mixed — reconciled against the real tree as of the `webdav-transport` feature (2026-06-03).** The first feature has landed code under `app/`, so this map now distinguishes **implemented** rows (observed in the working tree via `find app/src -type f`) from **planned** rows (their features have not run yet). The actual package root is `org.openwebdav.messenger` (so `app/.../transport/` = `app/src/main/kotlin/org/openwebdav/messenger/transport/`). The honesty rule stands: keep this map honest against the real tree — implemented rows are facts, planned rows are intentions, and any new divergence is a finding to fix here. (`git ls-tree -r --name-only HEAD` is still empty because the feature branch is uncommitted; the map is reconciled against the working tree directly.)

| Directory / module | Status | Responsibility |
|---|---|---|
| `app/` | **Implemented** (`webdav-transport`) | Single Android application module (Gradle), all source under it; namespace/package root `org.openwebdav.messenger` (stub `OpenWebDavMessengerApp` + build files present). |
| `app/.../transport/` | **Implemented** (`webdav-transport`) | OkHttp + WebDAV layer — PROPFIND/MKCOL/GET/PUT/DELETE, ETag/`If-Match` conditional writes, 429 back-off. Observed: `WebDavTransport`, `WebDavRequests`, `WebDavResult`, `CallExecutor`, `TransportFactory`, `ConnectionConfig`, `BackOff`, `PropfindParser`. |
| `app/.../protocol/` | **Implemented** (`webdav-transport`) | On-disk protocol model — inbox fan-out, message envelope encode/decode, codec id, ordering. Implements the `docs/protocol/webdav-layout.md` spec. Observed: `Envelope`, `MessageId`, `OrderToken`, `ChatPaths`, `Base32`. |
| `app/.../ui/` | Planned (feature not yet run) | Jetpack Compose chat surface — chat list, message list, composer, settings (incl. polling-interval control). State-hoisted; no I/O in composables. |
| `app/.../crypto/` | Planned (feature not yet run) | libsodium wrappers — Argon2id passphrase→key derivation, XChaCha20-Poly1305 AEAD seal/open, the three key sources (known/random/passphrase, decision 9). No keys leave this layer in extractable form. |
| `app/.../identity/` | Planned (`identity` feature not yet run — coder creates it this feature) | Per-user identity substrate (decision 10): Ed25519 signing + X25519 box keypairs (generate/load/store, secret keys Keystore-wrapped), X25519 key agreement (`crypto_box_beforenm` → KDF → `ChatKey`, the fourth key source feeding `crypto/`'s AEAD), sealed-box (`crypto_box_seal`, sender-unauthenticated — the rotation primitive), Ed25519 detached signing/verify, and the symmetric BLAKE2b verification fingerprint. Package root `org.openwebdav.messenger` (so `app/.../identity/` = `app/src/main/kotlin/org/openwebdav/messenger/identity/`). Pure crypto: no on-disk format, publishes nothing. |
| `app/.../keystore/` | Planned (feature not yet run) | Android Keystore wrap/unwrap of derived chat keys; device-local only. |
| `app/.../message/` | Planned (`message-model` feature not yet run — coder creates it this feature) | Inner message model (decision 11): `TextMessage` / `ReactionMessage` types + a `Message` sum type, the versioned TLV serialize, `signAndSerialize` (Ed25519 detached sign with the sender identity key from `identity/`), and `parse` (deserialize + `verify_detached`, returning a typed `Rejected` on bad signature / malformed / unknown version|kind / out-of-range index — never throws). Implements `docs/protocol/webdav-layout.md` §8; composes with `crypto/`'s AEAD (the signed bytes are the sealed plaintext, `codec-id = 0x00`). Package root `org.openwebdav.messenger` (so `app/.../message/` = `app/src/main/kotlin/org/openwebdav/messenger/message/`). Pure JVM-testable logic; no on-disk write (that is `sync`). |
| `app/.../codec/` | Planned (feature not yet run) | Compression — DEFLATE compress/inflate, bounded decompression, per-message. |
| `app/.../markdown/` | Planned (feature not yet run) | Hand-rolled `AnnotatedString` parser for the 6-element subset + link scheme allowlist. |
| `app/.../data/` | Planned (feature not yet run) | Room cache (entities, DAOs, migrations, schema export) for messages and protocol/key metadata. |
| `app/.../sync/` | Planned (feature not yet run) | WorkManager worker(s) — one-poll-cycle sync over the inbox; optional foreground-service mode (pending decision 6). |
| `docs/` | Implemented | Project docs (this file, stack-notes, product funnel, threat model, user journeys; `docs/protocol/webdav-layout.md` authored by `webdav-transport`). |
| `.ai-pm/` | Implemented | AI-PM tooling, contracts, arch notes. |

## Integration contract

This is an Android app installed from an APK, not a library or service with downstream code consumers. There is no exported API, no `pip install`-style entry point, no env contract for callers.

- **Build / install (aligned with `README.md`):** built with Gradle (`./gradlew assembleDebug`); the deliverable is an Android APK installed on a device. No install-time external integration surface.
- **Runtime integration — WebDAV disk:** the app integrates with a user-supplied WebDAV share (Yandex.Disk app-password/OAuth, or Nextcloud at `/remote.php/dav/files/{user}/...`) entirely at runtime via PROPFIND/MKCOL/PUT/GET/DELETE over OkHttp. The load-bearing contract for *interoperability between app instances* is the on-disk protocol layout — **`docs/protocol/webdav-layout.md`** (not yet authored; see decision 2 and Behavioral contract). Until that exists, treat the invariants below as the contract.
- **Runtime integration — Android Keystore:** keys generated on-device via `KeyGenParameterSpec`; no separate artifact file. (stack-notes → Integration contracts table.)

## Behavioral contract (taxonomies & invariants)

The single home for this project's domain taxonomies and format invariants. The byte/path-level layout is specified in **`docs/protocol/webdav-layout.md`** (authored by the first transport feature, 2026-06-03); that file is the **authoritative source** for message-id grammar, inbox/file naming, the ordering token, the envelope framing, and the inner message plaintext format (§8, message-model feature) — the entries below state each invariant in one line and point there (move-not-copy, do not restate the grammar in two places). `docs/user-journeys.md` and contracts must reference, not restate, these invariants. Remaining `[?]` items (chat-id grammar, reaction glyphs) are pinned by their own later features.

> **Identity on-disk format is NOT specified by the identity substrate (decision 10).** The identity feature is pure crypto and defines no WebDAV layout. The on-disk format for **published identity entries** (signed public keys) and for **sealed rotation payloads** will be added to `docs/protocol/webdav-layout.md` by the **directory** and **rotation** features respectively — not by the identity feature.

### Chat taxonomy (two-axis: kind × access)

The earlier flat `chat-type` enum `{private, public}` was too narrow — it conflated *who a chat is with* (1:1 vs group) with *whether content is hidden from the disk operator*. The taxonomy is now **two orthogonal axes** plus an optional password:

- **Kind:**
  - `dm` — a 1:1 chat. **Always private — a DM cannot be public.**
  - `group` — a multi-member chat. **May be public or private.**
- **Access:**
  - `public` — **group only**; readable by **anyone with the app + chat-id**. **NOT secret** — the disk operator and anyone with access can read it. UI must warn and nudge toward a private chat.
  - `private` — a **DM or a group**; content is **hidden from the disk operator** (AEAD ciphertext only on disk).
- **Password (optional):** a **private** chat (DM **or** private group) may be **passphrase-protected or not**:
  - private **without** a password → a CSPRNG **random** key distributed out-of-band (future invite).
  - private **with** a password → a key derived from the passphrase.

**Valid combinations:** `dm` is always `private` (with or without a password). `group` is `public`, or `private` (with or without a password). `dm + public` is **invalid**.

**Mapping onto the three crypto key sources** (substrate, decision 9 — this is *how* each cell is keyed):

| Kind | Access | Password | Key source |
|---|---|---|---|
| `group` | `public` | — | **known** = `KDF(in-app constant ‖ chat-id)` (not secret) |
| `dm` / `group` | `private` | no | **random** = CSPRNG key, distributed out-of-band (future invite) |
| `dm` / `group` | `private` | yes | **passphrase** = `Argon2id(passphrase, salt)` |

Changing these axes or their valid combinations is an architectural decision. The byte layout that carries a sealed message is identical for all three key sources (`docs/protocol/webdav-layout.md` §5.1); the key source is determined by the chat's `kind`/`access`/password config, not encoded in the envelope.

**Source:** PM decision, bootstrap+crypto planning conversation, 2026-06-03 (supersedes the MVP 2-value `chat-type` enum); key-source mapping per decision 9 / `docs/features/crypto_plan.md`.

### Reaction enum

Exactly **5 fixed reactions** (MVP). The **index space is fixed** by the message model (decision 11): a reaction carries a `reaction-index` in the **closed range 0..4**, and any reaction message must validate against it (an index ∉ 0..4 = typed rejection). The concrete glyph/identifier for each of the 5 indices is still `[?]` — a **UI concern**, to be fixed by the reactions/UI feature; the wire format fixes only the 5-value index space (`docs/protocol/webdav-layout.md` §8.5, authoritative).

### Message envelope (conceptual fields)

Every message written to a WebDAV inbox is an AEAD ciphertext whose plaintext, once opened, is a **versioned, signed, TLV message** with a `kind` tag — the inner field list, the per-kind fields, the per-message signature, and the reaction index space are all **fixed by decision 11**, with the authoritative byte layout in **`docs/protocol/webdav-layout.md` §8** (move-not-copy — do not restate the byte layout here). In one line per field:

- **message-id** — `order-token "~" content-hash` (content-addressed; stable, unique, never reused; ordering/dedup key). The on-disk file name **is** the message's id (§2); there is **no inner self-id** — `reply-to`/`target-id` reference *other* messages by their §2 file names (one id space; `docs/protocol/webdav-layout.md` §8.6). A writer PUTs a new content-addressed file and never overwrites a prior one (decision 2; Inbox invariants). Grammar/alphabet/length: **`webdav-layout.md` §2**; identity (no inner self-id, references = §2 file names): **§8.6** (authoritative).
- **message-format version + kind** — a 1-byte `msg-format-version` + 1-byte `kind` ∈ **{text, reaction}** (closed for now; `edit`/`delete`/`system` reserved, not defined). Unknown version/kind = typed rejection (reject-don't-guess). **`webdav-layout.md` §8.1–§8.2** (authoritative).
- **sender (identity public key) + signature** — every message carries the **sender's identity Ed25519 public key (32 bytes)** and a **64-byte Ed25519 detached signature** over the serialized fields (intra-chat sender authentication, decision 11 / decision 10). Verification is hard-reject on failure. **`webdav-layout.md` §8.3** (authoritative). (This supersedes the MVP "sender = display/address identifier, no verified key" note: the message now carries a verifiable sender key, though *binding that key to a human* remains the future directory's job.)
- **chat-id** — grammar `[?]` (identifies the chat; inbox/folder naming derives from it) — **still open**, carried length-prefixed/opaque in §8 until its own feature pins the grammar.
- **chat taxonomy fields** — the chat's `kind` (`dm`/`group`) and `access` (`public`/`private`), plus whether it is password-protected (see *Chat taxonomy* above). These describe the chat, not each message; the per-chat descriptor lives in `meta/chat.json` (`docs/protocol/webdav-layout.md` §1.3, populated by a later roster feature).
- **reply-to** (text only) — **optional**; the full §2 file name of the message this text quotes; absent = not a reply; may precede its target (graceful degradation, §4). **`webdav-layout.md` §8.4** (authoritative).
- **target-id + reaction-index** (reaction only) — a reaction message carries the reacted-to message's **full §2 file name** (`target-id`) and a **reaction-index in the closed range 0..4** (the fixed 5-reaction set; glyphs are UI). **`webdav-layout.md` §8.5** (authoritative).
- **body** (text only) — UTF-8 text + the supported Markdown subset, **raw** (no rendering — UI feature). Optionally compressed-then-encrypted by a later codec; carried verbatim here. **`webdav-layout.md` §8.4** (authoritative).
- **send-timestamp** (text only) — sender wall-clock unix-millis, **display-only / best-effort**, never trusted, never used for ordering. **`webdav-layout.md` §8.4** (authoritative).
- **codec id** — which codec compressed the body before encryption: `{none, deflate}` (decision 4). Carried in the **outer** envelope frame's `codec-id` byte (NOT the inner plaintext); reader inflates per this field; unknown codec = error path, not a guess; for the message-model feature it stays `none` (0x00). Frame byte layout: **`docs/protocol/webdav-layout.md` §5** (authoritative).
- **ordering token** — a monotonic, best-effort `order-token` = millisecond timestamp + per-sender tag + per-sender sequence; it is the sortable prefix of the message-id and the **only** ordering key (the inner send-timestamp is display-only). Exact form and tie-break: **`docs/protocol/webdav-layout.md` §4** (authoritative); summary under *Ordering & causality* below.

### Inbox / file-naming invariants

- **Per-user inbox fan-out:** a sender writes a copy of each outgoing message into **every recipient's** inbox. A client reads only **its own** inbox per poll cycle. Inbox path = `inbox/<recipient-inbox-id>/`, where `recipient-inbox-id` is a deterministic hash of the recipient identifier + chat-id (so any sender computes a recipient's inbox path to fan out). Exact derivation: **`docs/protocol/webdav-layout.md` §1.2** (authoritative).
- **The inbox is a read-efficiency layout, NOT an access-control boundary.** Under the single shared WebDAV credential (decision 2, Topology A) every member is the same disk identity, so every member can read **and delete** every file in the shared space — including other members' inboxes. The fan-out exists only to make a poll cycle one cheap `PROPFIND Depth: 1` per client, not to isolate members from each other.
- **Append-only, content-addressed message files:** the message file name **is** the message-id (whose `content-hash` suffix = SHA-256 of the file bytes), and a writer only ever **PUTs a new file — never modifies or overwrites an existing one**; a reader rejects a file whose recomputed hash does not match its name. This append-only, content-addressed discipline is the integrity compensation for the flat trust model: the transport gives no per-author protection on any provider (decision 2), so files are never mutated in place. Naming + on-read check: **`docs/protocol/webdav-layout.md` §2–§3** (authoritative).
- **Conditional write:** every write that risks a lost update uses ETag + `If-Match`; a competing-writer 412 is a normal retry path, not a crash.

### Ordering & causality

- Clients must tolerate out-of-order arrival and duplicate delivery (fan-out + polling can re-deliver); **message-id is the dedup key**.
- A reply (`reply-to`) may arrive before the message it quotes; the UI must degrade gracefully (show as reply to an unknown/unloaded message), not error.
- Ordering is best-effort over a **monotonic per-sender `order-token`** (timestamp + sender tag + per-sender sequence); the total order and tie-break (by the lexicographically-sortable message-id, falling back through sender-tag, seq, then content-hash) are fixed in **`docs/protocol/webdav-layout.md` §4** (authoritative). Dedup remains by message-id; a `reply-to` may arrive before its target and must degrade gracefully.

### Decompression bound

- Decompressed plaintext size is bounded (zip-bomb defense); exceeding the bound is an error path. The numeric bound is `[?]` — to be fixed alongside the codec in `webdav-layout.md`.

## Release flow

N/A — no automated release pipeline exists. The repository has no commits yet, `.github/workflows/` is absent, and there is no `auto-tag.yml` or equivalent CI. The deliverable is a Gradle-built APK (`./gradlew assembleDebug`). When release automation is introduced, this section and CI must be authored together. (Decision 7 flags the related need for a CI emulator before `connectedAndroidTest` can gate releases.)

## Security constraints

The enforceable implementation rules — the **rule layer**. This is a security-bearing project (`docs/threat-model.md` is present), so each rule carries a stable `SCn` ID; the threat-model's Threats-table Mitigation column references these IDs (`threat → constraint`, one-way, by ID — no rule text is duplicated into the threat-model). IDs are assigned once and never renumbered; a new rule gets the next free `SCn`.

Source: PM stack Q&A + `CLAUDE.md` Security constraints + `docs/stack-notes.md` security gotchas (bootstrap conversation, 2026-06-03); transport/crypto/message-model rules from the landed substrates and `docs/protocol/webdav-layout.md`.

- **SC1 — Private-chat content:** end-to-end encrypted with XChaCha20-Poly1305 AEAD; per-chat key from one of three sources — passphrase via Argon2id (memory-hard), a CSPRNG random key, or a known KDF'd key for public chats (decision 9). The disk stores ciphertext only.
- **SC2 — Public chats are explicitly NOT secret:** known/well-known key (`KDF(in-app constant ‖ chat-id)`). The UI must clearly warn that the chat is readable by the disk operator and anyone with access, and nudge toward a password-protected private chat for real conversations.
- **SC3 — The disk app-password is NEVER a content key (decision 9).** The disk operator (e.g. Yandex) receives the WebDAV app-password to authenticate every request; deriving a content key from it would let the operator decrypt all content. Content keys (known/random/passphrase) are always **independent of disk access** and are never derived from the disk credential.
- **SC4 — Content keys are Keystore-wrapped, never on disk.** Passphrases and derived/random chat keys are stored only device-local, **wrapped via Android Keystore directly**, and are **never written to the WebDAV disk and never logged**. `androidx.security:security-crypto` (EncryptedSharedPreferences) is **deprecated (1.1.0, 2025)** and must not be used for new storage. (No double-layer "app-key under passphrase" — the app key is APK-extractable, so it is obscurity not security; decision 9.)
- **SC5 — Identity secret keys are Keystore-wrapped, never on disk (decision 10).** The per-user identity **secret** keys — the Ed25519 signing `sk` and the X25519 box `sk` — are device-local secret material under the **same** rule as chat keys: wrapped via Android Keystore directly, **never written to the WebDAV disk and never logged** (stored under a distinct alias so they do not disturb chat keys). The identity **public** keys (Ed25519 `pk`, X25519 `pk`) are **publishable** — they are the material a future directory entry exposes.
- **SC6 — Compression side channel:** compress each message independently; never co-compress secret + attacker-influenced data in one context (CRIME/BREACH class). Length-hiding/padding to be considered in `webdav-layout.md`.
- **SC7 — Untrusted decompression:** bound decompressed size (zip-bomb guard); decompression failure is a normal error path, not a crash.
- **SC8 — Untrusted Markdown:** no raw-HTML passthrough, no remote image auto-loading, link scheme allowlist (e.g. `https`/`http`/`mailto`) with the real URL visible, navigation only on explicit tap, no autolinkification.
- **SC9 — No hand-rolled crypto:** audited primitives only.
- **SC10 — Java-interop nullability:** crypto/HTTP libraries are Java; values crossing the boundary are platform types and must be treated as nullable — no `!!` on WebDAV/crypto paths.
- **SC11 — Flat trust model (shared disk credential, decision 2):** all members of a chat share one WebDAV credential, so **any member can technically delete or overwrite another member's message files**. This is an accepted, deliberate MVP limitation — all members already share the chat passphrase, so trust is "knows the passphrase = insider". AEAD provides tamper-detection of message **content**, but **not** deletion resistance. Protection against deletion (signatures, replicated/witnessed logs) is future work. Append-only, content-addressed files (Behavioral contract) limit silent in-place mutation but do not stop deletion.
- **SC12 — Identity keys (X25519 + Ed25519) for contact verification (decision 10):** the identity substrate is now implemented — per-user Ed25519 + X25519 keypairs give cryptographic key agreement, anonymous sealed encryption, detached signatures, and a verification fingerprint. **Sealed-box is sender-unauthenticated**, so any feature relying on sender provenance (notably key rotation) MUST add an Ed25519 detached signature; sealed-box alone proves only that the recipient could open the payload. In the current MVP message path, message authenticity still rests on AEAD + the shared passphrase only — wiring identity keys into a verified per-message sender is decision 11 (SC15).
- **SC13 — TLS enforced; cleartext rejected.** All WebDAV transport runs over HTTPS; the OkHttp client must reject cleartext HTTP (no plaintext credential or ciphertext on the wire). The shared app-password authenticates every WebDAV request and must never traverse an unencrypted connection. (Transport hardening; `docs/protocol/webdav-layout.md` §6 — generic WebDAV + Yandex.Disk over TLS.)
- **SC14 — Bounded reads against DoS.** A `GET` of a message file is size-capped (1 MiB) and the §8 plaintext parser caps the TLV `field-count`; an oversized body or an inflated field-count is a typed reject / not-ready path, never an unbounded allocation or a crash. This bounds the resource cost of any single untrusted file pulled from the disk (decision 11 reject-don't-guess; `docs/protocol/webdav-layout.md` §8.1). Complements SC7 (decompression bound).
- **SC15 — Per-message Ed25519 signature for intra-chat sender authentication (decision 11).** Every message carries the sender's identity Ed25519 public key and a 64-byte detached signature over the serialized fields; verification failure (libsodium `-1`) is a **hard reject**, never best-effort. The shared AEAD key (SC1) proves only that *some* chat member sealed the message; the signature is what distinguishes *which* member, closing intra-chat impersonation (a member cannot sign as another). The whole signed message rides inside the AEAD ciphertext-blob, so the sender key and signature are confidentiality-protected too. (`docs/protocol/webdav-layout.md` §8.3.)
- **SC16 — Path-traversal rejection on disk references.** Every path segment the protocol mints or accepts (chat-root, `recipient-inbox-id`, message-id / file name, and the `reply-to`/`target-id` references that carry other messages' §2 file names) is restricted to the filename-safe alphabet `[A-Za-z0-9._-~]` with no `/`, `..`, spaces, or escape characters; a value outside the §0/§2 grammar is rejected, never dereferenced. This stops a crafted reference or chat-root from escaping the chat folder on the WebDAV server. (`docs/protocol/webdav-layout.md` §0 conventions + §2 message-id grammar + §8.4/§8.5 reference validation.)

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

Prefer the Android/Kotlin standard library and Jetpack. Add a third-party dependency only when it provides an audited capability the stdlib lacks (crypto) or saves substantial non-trivial code, and is actively maintained. **Treat any dependency that bundles a native `.so` per ABI as a packaging risk** (`UnsatisfiedLinkError`) requiring instrumented verification. License each addition consciously and check compatibility with the project license — **AGPL-3.0** (PM decision 2026-06-03; see `LICENSE`): a copyleft license, so dependencies must be license-compatible with AGPL-3.0 distribution.

### Current dependencies

> **Reconciled against `app/build.gradle.kts` + `gradle/libs.versions.toml` as of the `webdav-transport` feature (2026-06-03).** The build files now exist, so the rows below are the **actually declared** dependencies/plugins (versions from the version catalog). Libraries implied by later stack decisions but **not yet added** to the build (lazysodium-android, JNA, Jetpack Compose, WorkManager, Room) are listed separately below — they remain forward targets, not current dependencies. `java.util.zip` is stdlib (no coordinate).

Build toolchain (from `gradle/libs.versions.toml` + wrapper):

| Tool | Version | Added |
|---|---|---|
| Android Gradle Plugin (`com.android.application`) | 8.7.3 | `webdav-transport` (2026-06-03) |
| Kotlin (`org.jetbrains.kotlin.android`) | 2.0.21 | `webdav-transport` (2026-06-03) |
| ktlint Gradle plugin (`org.jlleitschuh.gradle.ktlint`) | 12.1.2 | `webdav-transport` (2026-06-03) |
| Gradle wrapper distribution | 8.13 | `webdav-transport` (2026-06-03) |
| JDK toolchain / `jvmTarget` | 17 | `webdav-transport` (2026-06-03) |

Declared library dependencies:

| Package | Coordinate (version) | Config | Purpose | Added |
|---|---|---|---|---|
| androidx.core KTX | `androidx.core:core-ktx:1.13.1` | implementation | Android KTX extensions | `webdav-transport` (2026-06-03) |
| kotlinx-coroutines-core | `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` | implementation | Off-main-thread concurrency | `webdav-transport` (2026-06-03) |
| kotlinx-coroutines-android | `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` | implementation | Android main-dispatcher integration | `webdav-transport` (2026-06-03) |
| OkHttp | `com.squareup.okhttp3:okhttp:4.12.0` | implementation | WebDAV transport (PROPFIND/GET/PUT/DELETE, conditional writes, back-off) | `webdav-transport` (2026-06-03) |
| JUnit 4 | `junit:junit:4.13.2` | testImplementation | Unit test framework | `webdav-transport` (2026-06-03) |
| OkHttp MockWebServer | `com.squareup.okhttp3:mockwebserver:4.12.0` | testImplementation | WebDAV transport tests (verb/concurrency/interaction) | `webdav-transport` (2026-06-03) |
| kotlinx-coroutines-test | `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0` | testImplementation | Coroutine test dispatchers | `webdav-transport` (2026-06-03) |
| `java.util.zip` (stdlib) | — (JDK) | — | DEFLATE compression — zero dependency | (stdlib; available, no coordinate) |

Stack-decision libraries **not yet added** to the build (forward targets — to be added by their features):

| Package | Purpose | Status |
|---|---|---|
| Jetpack Compose | UI toolkit | Not yet added (ui feature) |
| androidx.work (WorkManager) | Periodic background polling | Not yet added (sync feature) |
| com.goterl:lazysodium-android + net.java.dev.jna:jna | Argon2id KDF + XChaCha20-Poly1305 AEAD (native `.so`); also the public-key primitives (crypto_box / crypto_sign / sealed-box / generichash) the identity substrate uses (decision 10) — with `com.goterl:lazysodium-java` for JVM tests | Not yet added (crypto / identity features) |
| androidx.room | Local offline-first cache | Not yet added (data feature) |
| (markdown) | Hand-rolled `AnnotatedString` parser — no dependency | Not yet added (markdown feature; no coordinate planned) |
