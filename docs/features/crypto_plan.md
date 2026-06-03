# crypto — plan

> The E2E-encryption **substrate**. Fills the `ciphertext-blob` slot reserved by `webdav-transport` in the envelope (`docs/protocol/webdav-layout.md` §5). It provides **key primitives + AEAD seal/open + Keystore storage**, operating on OPAQUE plaintext bytes. It is agnostic to the chat taxonomy (DM/group, public/private, password-or-not) — the chat-model/UI features map a chat's configuration onto one of the key sources below. No UI, no invite/QR encoding, no directory. Backend/infrastructure.

## Scenarios

System behaviors (no UI yet — the security core the chat-model, invites, and X25519 build on):

1. **Three key sources, one AEAD.** The substrate can obtain a per-chat symmetric key three ways, and seals/opens with whichever it is given:
   - **From a passphrase** — Argon2id(passphrase, salt) (memory-hard). Same passphrase + chat-id → same key, so members derive an identical key independently.
   - **Random** — a freshly generated CSPRNG key (for chats whose key is shared via an invite rather than a passphrase; the invite/QR encoding that carries this key is a separate feature).
   - **Known-from-chat-id** — KDF(in-app constant ‖ chat-id), no secret input (for public chats — readable by anyone with the app + chat-id; "not secret" by design).
2. A plaintext byte payload is **sealed** (AEAD: XChaCha20-Poly1305) with the chat key into the envelope `ciphertext-blob`, and **opened** back to the exact original bytes. One AEAD layer regardless of key source.
3. The 8-byte envelope header (magic/version/codec-id/flags/reserved) is bound as AEAD **associated data (AAD)**, so tampering with codec-id/flags/version breaks the tag and `open` fails.
4. Opening with the **wrong key** (wrong passphrase, wrong random key, or tampered ciphertext) fails as a **typed rejection** — never a crash, never silently-wrong plaintext.
5. A nonce is freshly random (24 bytes) per seal, placed at the start of the blob, so two seals of identical plaintext differ (upholds the transport's content-addressing invariant, §2).
6. A chat key is stored **device-local, wrapped via Android Keystore**; passphrases and raw keys are never written to the WebDAV disk and never logged.

> The disk credential (Yandex app-password) is **never** used as a content key: the disk operator receives that password to authenticate, so deriving the content key from it would let the operator read everything. Content keys are always independent of disk access. (Recorded in architecture as a security rationale.)

## Existing behaviors this feature touches

`docs/user-journeys.md` is a skeleton (no journeys). Concrete touch points are code/contract:
- **Envelope framing** (`protocol/Envelope`): this feature fills the `ciphertext-blob` and binds the 8-byte header as AAD. The envelope's existing magic/version/codec-id/reject-don't-guess behavior must keep working — all `webdav-transport` tests stay green.
- **Content-addressing** (`webdav-transport`, §2): the transport content-hashes the FINAL envelope bytes; the random per-seal nonce keeps identical plaintexts producing distinct ids.

## Contracts

New, internal (the on-disk blob byte layout is pinned in `docs/protocol/webdav-layout.md` §5 by the architect as part of this feature):

- **Key acquisition** — three operations returning a Keystore-backed `ChatKey` handle:
  - `keyFromPassphrase(passphrase, chatId) → ChatKey` — Argon2id; salt derived deterministically from chat-id.
  - `newRandomKey() → ChatKey` — CSPRNG-generated symmetric key.
  - `knownKey(chatId) → ChatKey` — KDF(app-constant ‖ chat-id).
  Plus import/export of a raw key (for the future invite feature to carry a random key) and persistence: `wrap/store(ChatKey)` / `load(chatId) → ChatKey?` via Android Keystore.
- **AEAD** — `seal(chatKey, header8, plaintext) → blob` and `open(chatKey, header8, blob) → Opened(bytes) | Rejected`. `blob = nonce(24) ‖ AEAD-ciphertext+tag`; `header8` bound as AAD. `open` returns `Rejected` on any auth failure (wrong key / tampered header or ciphertext / truncated blob).

## Stack expectations touched

From `docs/stack-notes.md` (Last reviewed 2026-06-03):

- **Crypto library (libsodium)**: "libsodium's default algorithm is Argon2id … Presets: INTERACTIVE (64 MiB), MODERATE (256 MiB), SENSITIVE (1024 MiB)." Use Argon2id for passphrase→key. Source: https://doc.libsodium.org/password_hashing/default_phf
- **Crypto library (libsodium)**: "libsodium provides XChaCha20-Poly1305 AEAD (192-bit nonce — safe to pick at random). Clean AEAD with associated data." Source: https://doc.libsodium.org/secret-key_cryptography/aead
- **Crypto library (lazysodium-android)**: "ships native `.so` via JNA … missing an ABI = `UnsatisfiedLinkError` at runtime on that device. Verify on real ABIs." → mandatory `connectedAndroidTest`. Source: https://github.com/terl/lazysodium-android
- **Crypto library**: "Argon2id … is intentionally slow … run it off the UI thread and pick a preset that survives low-RAM devices." Source: https://doc.libsodium.org/password_hashing/default_phf
- **Android Keystore**: "keeps key material out of the app process … Use it to wrap the chat keys rather than storing raw key bytes." Source: https://developer.android.com/privacy-and-security/keystore
- **Android Keystore**: "`androidx.security:security-crypto` … is DEPRECATED … use Android Keystore directly." Do not use EncryptedSharedPreferences. Source: https://developer.android.com/jetpack/androidx/releases/security
- **Android Keystore**: "Never persist a derived chat key or passphrase to the WebDAV disk — only to Keystore-wrapped app-private storage." Source: https://developer.android.com/privacy-and-security/keystore
- **Kotlin**: "Blocking I/O (network, disk, crypto KDF) must not run on the main/UI dispatcher … Isolate the Argon2id KDF." Source: https://kotlinlang.org/docs/coroutines-and-channels.html
- **Integration (envelope)**: artifact `docs/protocol/webdav-layout.md` §5 (nonce placement, AAD binding, blob layout) authored by architect as part of this feature; validated by JVM AEAD tests + on-device `connectedAndroidTest`.

## Interaction scenarios

Crypto integrates with the envelope/transport seam and shared key state; not isolated.

- **A message whose 8-byte header was altered on the disk** (any member can overwrite under the shared credential): the AAD binding makes `open` fail → `Rejected`, message dropped, no crash (in addition to the transport's content-hash check).
- **A reader opens a message sealed by another client** with the same key and codec-id=0x00: `open` succeeds and returns the exact bytes — cross-client interop holds.
- **Slow Argon2id derivation runs while the poll loop / a send is in flight:** derivation is off the UI thread (Dispatchers.IO); the derived key is cached per chat after first derivation so concurrent sends/receives do not re-run Argon2id.
- **Wrong passphrase for a passphrase-keyed chat:** every `open` returns `Rejected` (not a crash, not garbage), which the future UI surfaces as "wrong password".

## Test plan

- Existing tests that must pass: all `webdav-transport` tests stay green.
- New tests (JVM unit — lazysodium-java backed by the host's system libsodium, or the crypto interface behind a libsodium-backed impl):
  - `seal_open_roundtrip`: sealed then opened bytes equal the input exactly.
  - `header_is_bound_as_aad`: flipping one byte of the 8-byte header before open → `Rejected` (AEAD auth fails) — verifies §5 AAD binding.
  - `ciphertext_tamper_fails`: flipping a ciphertext/tag byte → `Rejected`.
  - `wrong_key_fails_open`: opening with a key from a different passphrase → `Rejected`, not wrong plaintext, not crash.
  - `nonce_is_random_per_seal`: two seals of identical plaintext with the same key → different blobs (different 24-byte nonce).
  - `passphrase_key_is_deterministic`: same (passphrase, chat-id) → identical key; different chat-id → different key.
  - `known_key_is_deterministic_from_chatid`: same chat-id → identical known key (no passphrase); different chat-id → different key.
  - `random_key_is_unique`: two `newRandomKey()` calls → different keys (CSPRNG).
  - `truncated_blob_is_rejected`: a blob shorter than nonce+tag → `Rejected`, not an index crash.
- Instrumented tests (`connectedAndroidTest` on the connected device 5c3ff0):
  - `native_lib_loads`: lazysodium-android loads on the device ABI with no `UnsatisfiedLinkError`.
  - `argon2id_on_device_deterministic`: Argon2id runs on-device, deterministic for the same inputs, completes within a reasonable bound for the chosen preset.
  - `keystore_wrap_unwrap_roundtrip`: a key wrapped via Android Keystore and unwrapped yields the same key; the raw key is not retrievable in plaintext from storage.
  - `native_aead_roundtrip`: real lazysodium-android XChaCha20-Poly1305 seal/open on-device.
- Stack-spec tests (verify the cited rule; reference the source URL in a comment):
  - `aead_is_xchacha20_poly1305`: the AEAD is XChaCha20-Poly1305 (24-byte nonce), not AES-GCM — architecture decision #1 / libsodium AEAD.
  - `kdf_is_argon2id`: passphrase key derivation uses Argon2id (memory-hard), not a fast hash — libsodium pwhash.
  - `raw_key_never_persisted`: no code path writes a raw key or passphrase to a file / the transport / logs — keys exist only Keystore-wrapped.

## Docs to update

- **`docs/protocol/webdav-layout.md` §5** (owner: `pm-architect`): fill the reserved crypto decisions — `blob = nonce(24) ‖ AEAD-ciphertext+tag`, the 8-byte header as AAD, XChaCha20-Poly1305, codec-id stays 0x00.
- **`docs/architecture.md`** (owner: `pm-architect`):
  - **Revise the chat taxonomy** (Behavioral contract `chat-type` enum is currently `{private, public}` — too narrow). New model: **Kind** = `dm` (1:1, always private) | `group` (public or private); **Access** = `public` (group only, known key, not secret) | `private` (random key or passphrase key); **password** optional on private. Record that the substrate exposes three key sources (known / random / passphrase) that these map onto.
  - **Record this feature's decisions**: Argon2id INTERACTIVE preset; passphrase salt deterministic from chat-id (future roster may move to a stored random salt); known-key = KDF(in-app constant ‖ chat-id) (non-secret, no build-secret injection); single-layer AEAD (double-layer rejected — app key is APK-extractable); keys Keystore-wrapped; **the disk app-password must never be a content key** (the disk operator holds it).
  - **Note the planned follow-ons** (so the substrate's seam is understood): **X25519** as a fourth key source enabling remote private chats between members already sharing a disk (publish public keys in a directory → DH → a symmetric key fed to this same AEAD); a **user/chat directory** on the disk for discovery; **invite/onboarding** (string + QR) carrying disk access + chat-id + (for random-key chats) the key. These are separate future features; this feature only makes the substrate ready for them.
- **`CLAUDE.md` Pipeline**: note `./gradlew connectedAndroidTest` is now an active gate for this feature, run on a connected device/emulator (verifies the native crypto `.so` + Keystore).

## Out of scope

- **Chat-model / taxonomy logic and UI** — choosing a chat's kind/access/password, the passphrase-entry and "not protected" warning screens, wrong-password feedback: separate features. This feature only provides the key primitives.
- **Invite / onboarding encoding (string + QR)** and the **user/chat directory** on the disk — separate features. This feature generates a random key and can import/export raw key bytes, but does not encode invites or manage discovery.
- **X25519 key agreement** — the next crypto feature; layers a fourth key source on top of this substrate (DH → symmetric key → this AEAD). Not implemented here.
- **Compression** — codec-id stays 0x00; the compression feature wires deflate later.
- **Sibling of the "chat-type" categorical** — both access modes (public/private) and all three key sources ARE covered here (the full set the substrate must provide); none deferred at the substrate level.
- **Group per-member key wrapping** — groups use one shared per-chat key; per-member distribution is deferred (and is where X25519 later helps).
- **Forward secrecy / double ratchet** — deferred; the genuinely-stronger private-chat path, a separate larger feature.
- **Double-layer encryption** (app-key under passphrase) — rejected: the app key is APK-extractable, so a second layer is obscurity, not security.
- **Build-secret injection for keys** — not used; the known-key constant is non-secret and lives in code. (The build-secret pattern arrives with a genuine secret, e.g. the future Telegram-gateway token.)
- **Directory metadata confidentiality** — the future directory will expose who-is-in-which-chat to the disk operator; encrypting it with a community key is a later concern.
