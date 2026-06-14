# Architecture

> The engineer's mental model of Open WebDAV Messenger — how the pieces fit and where to change them. Current state only; the *why* of a past decision lives in git. Readable in one sitting.

## What it is

A native Android text messenger with no dedicated server — it uses a user-supplied cloud disk (Yandex.Disk, Nextcloud, any WebDAV share) as its only transport. End-to-end encryption happens on the device (libsodium XChaCha20-Poly1305 AEAD) so the disk operator sees only ciphertext. Sync, fan-out, and ordering are all client-side over WebDAV PROPFIND/GET/PUT.

## Components & data flow

```
Device (Android)
  Compose UI ← Sync Engine ← Crypto/Identity ← Directory/ChatDirectory
                                  ↓
                          Room DB    Keystore
                                  ↓
            Transport (OkHttp: PROPFIND/GET/PUT/MKCOL/DELETE)
                                  ↓ HTTPS (TLS)
                    WebDAV Cloud Disk (untrusted — ciphertext only)
```

Message path: typed → signed (Ed25519) → compressed (DEFLATE) → AEAD-encrypted (XChaCha20-Poly1305) → content-addressed file name → PUT to `log/` on disk → per-member `changes/` entry written → peers poll `changes/` → fetch new `log/` → decrypt → decompress → verify → persist to Room → UI.

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Native Android, null-safety, coroutines |
| UI | Jetpack Compose | Declarative, full chat-surface control |
| Background sync | WorkManager | OS-friendly periodic polling (15-min floor) |
| Transport | WebDAV over OkHttp | PROPFIND/GET/PUT/DELETE, 429 back-off |
| Crypto | libsodium (lazysodium-android) | Argon2id + XChaCha20-Poly1305 AEAD (Tink lacks password-KDF) |
| Key storage | Android Keystore (direct) | Hardware-backed wrap; `security-crypto` deprecated |
| Local cache | SQLite via Room + SQLCipher at-rest encryption | Offline-first history, suspend/Flow, Keystore-wrapped AES-256 DB key |
| Compression | `java.util.zip` DEFLATE | Zero-dependency, compress-then-encrypt |
| Markdown | Hand-rolled `AnnotatedString` parser | 6-element subset, smallest surface |

## Behavioral contract (taxonomies & invariants)

Authoritative byte/path layout: **`docs/protocol/webdav-layout.md`**. This section states each invariant in one line and points there.

- **Chat taxonomy (kind × access):** `dm` (always private) or `group` (public/private). `public` = community-key-sealed, community-readable; `private` = per-chat key. Password optional.
- **Reaction enum:** 5 fixed reactions, index 0..4; glyphs are UI concern.
- **Shared-log + per-member change-index** (gen 2): one `log/<id>` write + (M−1) tiny `changes/<member>/` writes. Change index = read-efficiency not access-control — rebuild by listing `log/` (SC11). `webdav-layout.md` §1–§3, §9.
- **Append-only, content-addressed:** file name = content hash; writer only PUTs new files; reader recomputes hash. `webdav-layout.md` §2–§3.
- **Ordering & causal tolerance:** best-effort `order-token`; dedup by message-id; `reply-to` may precede target. `webdav-layout.md` §4.
- **Reject-don't-guess + bounded decompression:** unknown version/kind/codec = typed rejection; decompressed size capped (zip-bomb guard, bound `[?]`).

## Security constraints

Stable `SCn` IDs — the threat model references these by ID. Full prose in git history.

| ID | Constraint (one line) |
|---|---|
| SC1 | E2E AEAD (XChaCha20-Poly1305); ciphertext only on disk |
| SC2 | Public = community-barrier (community-key-sealed, community-readable, sealed from operator) |
| SC3 | Disk credential ≠ content key (app-password never derives any content key) |
| SC4 | Chat keys Keystore-wrapped, never on disk/logged |
| SC5 | Identity secret keys Keystore-wrapped, never on disk/logged; public keys publishable |
| SC6 | Per-message independent compression; never co-compress secret + attacker data |
| SC7 | Bounded decompression (zip-bomb guard); failure = error path, not crash |
| SC8 | Untrusted Markdown: no HTML/remote images, scheme allowlist, tap-only navigation |
| SC9 | Audited primitives only (libsodium); no hand-rolled crypto |
| SC10 | No `!!` on Java-interop crypto/HTTP paths |
| SC11 | Flat trust: any member can read/delete any file; AEAD detects tampering, not deletion |
| SC12 | Sealed-box is sender-unauthenticated → rotation payloads MUST be Ed25519-signed |
| SC13 | TLS enforced; cleartext HTTP rejected |
| SC14 | Bounded reads: 1 MiB GET + TLV field-count cap; typed reject |
| SC15 | Per-message Ed25519 signature; hard reject on verify failure |
| SC16 | Path-traversal rejection: safe alphabet, no `/`/`..`; reject, never dereference |
| SC17 | Local history: device-local app-private Room DB, encrypted at rest via SQLCipher + Keystore-wrapped AES-256 key |
| SC18 | Directory entries Ed25519-signed (hard reject), community-key-sealed; sig = authorship, not name↔human |
| SC19 | Community key: community-wide symmetric, independent of disk credential, never on disk/logged |
| SC20 | DMs never in chat directory; `dm` hard reject on read (social-graph privacy) |
| SC21 | No secrets in source/git history (absolute); CI-enforced (gitleaks) |

## Decisions

One line per decision. Detail in git history. OPEN items flagged (Operator to decide).

1. **Crypto library:** libsodium-only (Argon2id + XChaCha20-Poly1305); Tink lacks password-KDF.
2. **Disk topology:** Topology A — one shared WebDAV credential per chat (all members = one disk identity).
3. **Aggregated sync:** shared `log/` + per-member `changes/` + retention window (replaced v1 per-recipient inbox fan-out).
4. **Compression (Implemented — 2026-06-14):** DEFLATE (`java.util.zip`), compress-then-encrypt, per-message independent, codec-id in envelope.
5. **Markdown rendering:** hand-rolled `AnnotatedString` parser for 6 elements (smallest untrusted-input surface).
6. **PARTIALLY RESOLVED** — Polling: WorkManager background floor implemented, foreground-service fast mode deferred. Static analysis: ktlint chosen (detekt not used). **OPEN** — CI emulator for `connectedAndroidTest`.
7. **Crypto substrate:** 3 key sources (random/passphrase/DH); public-chat = community-key (world-readable tier retired 2026-06-06).
8. **Identity substrate:** Ed25519 (signing) + X25519 (box) keypairs; DH→KDF→ChatKey; sealed-box; BLAKE2b fingerprint.
9. **Message model:** versioned TLV plaintext; per-message Ed25519 signature; reaction = first-class msg kind (0..4); reject-don't-guess.
10. **Directory substrate:** community user directory — self-signed/community-key-sealed entries; superseded per signing-pubkey.
11. **Chat directory substrate:** group-only (DMs hard-rejected); self-signed/community-key-sealed; superseded per chat-id.
12. **Codec dedup:** shared parse cursor + shared community-directory engine + single-source constant homes.
13. **Local history encryption (Implemented — 2026-06-14):** Room DB encrypted at rest via SQLCipher + Keystore-wrapped AES-256 key; unencrypted→encrypted migration on first upgrade (ATTACH + sqlcipher_export).

## Architectural constraints

- Cloud disk is untrusted — private content must be AEAD-encrypted client-side.
- One shared WebDAV credential per chat (Topology A); append-only/content-addressed files compensate for flat trust.
- Keys/plaintext stay device-local (Keystore + app-private); only ciphertext crosses to disk.
- No dedicated backend in MVP; all sync/fan-out/ordering client-side over WebDAV.
- WebDAV is slow/rate-limited — minimise round-trips; shared-log + change-index; 429 back-off.
- No hand-rolled crypto. Compress-then-encrypt, per message. Untrusted content never auto-actioned.
- All blocking work off main thread (`Dispatchers.IO`).

## File layout (module map)

Package root: `org.openwebdav.messenger` under `app/src/main/kotlin/`.

| Module | Status | Responsibility |
|---|---|---|
| `transport/` | Implemented | OkHttp + WebDAV verbs, 429 back-off |
| `protocol/` | Implemented | Path minting, envelope, ordering, base32 |
| `crypto/` | Implemented | Argon2id KDF, XChaCha20-Poly1305 AEAD, key sources |
| `identity/` | Implemented | Ed25519 signing, X25519 box, DH→KDF, fingerprint |
| `keystore/` | Implemented | Keystore wrap/unwrap of chat + identity secret keys |
| `message/` | Implemented | Inner message model — TextMessage/Reaction, TLV, sign/verify |
| `directory/` | Implemented | Community user directory — signed/sealed entries |
| `chatdirectory/` | Implemented | Community chat directory — group-only descriptors |
| `data/` | Implemented | Room local cache — history + sync cursors |
| `sync/` | Implemented | Poll-cycle: send (log+changes), poll, background scheduling |
| `codec/` | Implemented | DEFLATE compress/inflate, bounded decompression |
| `markdown/` | Planned | Hand-rolled 6-element `AnnotatedString` parser |
| `ui/` | Planned | Compose chat surface |

## Extension points

- **Add a message kind:** extend §8 TLV enum in `webdav-layout.md`; new `Message` subclass + serialize/parse + UI.
- **Add a compression codec:** new `codec-id` in envelope; implement in `codec/`; update decompression bound.
- **Add a key source:** implement in `crypto/KeySources`; wire into `ChatKeyStore`.
- **Add a community-scoped collection:** follow `directory/` pattern — shared `CommunityDirectoryEngine<E>`, thin service face, collection-isolated paths.
- **Add a platform (iOS):** protocol layer is pure Kotlin; platform-specific surface is Keystore/Room/WorkManager/Compose.

## Release flow

PR-checks (`pr-checks.yml`): on every PR to `main`, runs `./gradlew test ktlintCheck lint` + gitleaks (SC21). Release (`release.yml`): on push to `main`, auto-tags, publishes debug APK. Idempotent. Debug-signing only; `connectedAndroidTest` not in CI.

## Code conventions

Max file 300 lines, max function 50, complexity ≤10. No file-level lint suppressions. ≥80% coverage for new code. Prefer `?.`/`?:` over `!!`. No blocking work on UI dispatcher. Compose: side-effect-free, state hoisted. One `OkHttpClient`, close bodies. Room: `suspend`/`Flow`, `exportSchema = true`. Linter: `./gradlew ktlintCheck lint`.
