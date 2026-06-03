# crypto — plan compliance review

Baseline: `docs/features/crypto_plan.md`, `docs/protocol/webdav-layout.md` §5/§5.1, `docs/architecture.md` (decisions 1/9 + Security constraints + chat taxonomy), `docs/stack-notes.md` (Crypto / Keystore / Kotlin), `CLAUDE.md`.

**Product Contract:** none touched — this is a backend/infrastructure substrate (no UI, no user-visible surface). `.ai-pm/contracts/` is empty; no contract is required for this feature. Stated explicitly per the review rules: **no Product Contract touched.**

## Plan compliance — Scenarios

- ✓ **Scenario 1 (three key sources, one AEAD)** — `KeySources.keyFromPassphrase` (Argon2id, salt from chat-id), `newRandomKey` (CSPRNG), `knownKey` (BLAKE2b(const ‖ 0x1F ‖ chat-id)); all return a 32-byte `ChatKey` feeding one `Aead`. Tests: `KeySourcesTest` (8) + `all_three_sources_produce_32_byte_keys`.
- ✓ **Scenario 2 (seal/open exact bytes, one AEAD layer)** — `Aead.seal/open`, `MessageCrypto.seal/openEnvelope`. Tests: `seal_open_roundtrip`, `seal_open_roundtrip_empty_plaintext`, `seal_envelope_has_valid_header_then_open_roundtrips`.
- ✓ **Scenario 3 (8-byte header bound as AAD)** — `Aead.seal` passes `header8` as AAD; `MessageCrypto` builds header first via `Envelope.header()`. Tests: `header_is_bound_as_aad` (all 8 bytes flipped), `tampering_codec_id_byte_in_envelope_rejects`.
- ✓ **Scenario 4 (wrong key / tamper → typed rejection, no crash, no wrong plaintext)** — `OpenResult.Rejected`; `LazySodiumCrypto.aeadDecrypt` returns null on auth failure. Tests: `wrong_key_fails_open`, `ciphertext_tamper_fails`, `nonce_tamper_fails`, `wrong_key_on_envelope_rejects`.
- ✓ **Scenario 5 (fresh 24-byte random nonce per seal, at blob start)** — `Aead.seal` calls `native.randomBytes(24)`, `copyInto(blob,0)`. Test: `nonce_is_random_per_seal` (asserts distinct blobs + distinct nonce prefixes, both still open).
- ✓ **Scenario 6 (Keystore-wrapped device-local; never on disk/logged)** — `ChatKeyStore` wraps with a Keystore AES/GCM key, writes only `iv ‖ ct+tag` to app-private storage; `ChatKey.toString` redacted. Tests: `keystore_wrap_unwrap_roundtrip` (instrumented), `raw_key_never_persisted` (JVM).

## Plan compliance — Test plan (behavior match; names may differ)

9 JVM unit:
- ✓ `seal_open_roundtrip` — AeadTest
- ✓ `header_is_bound_as_aad` — AeadTest (all 8 header bytes)
- ✓ `ciphertext_tamper_fails` — AeadTest
- ✓ `wrong_key_fails_open` — AeadTest
- ✓ `nonce_is_random_per_seal` — AeadTest
- ✓ `passphrase_key_is_deterministic` — KeySourcesTest (+ differs-by-chatid / differs-by-passphrase)
- ✓ `known_key_is_deterministic_from_chatid` — KeySourcesTest (+ differs-by-chatid)
- ✓ `random_key_is_unique` — KeySourcesTest
- ✓ `truncated_blob_is_rejected` — AeadTest (len 0,1,23,24,39 → Rejected, no bounds crash)

3 stack-spec (each cites a source URL):
- ✓ `aead_is_xchacha20_poly1305` — asserts XChaCha20-Poly1305 IETF constants (24/32/16) and a real seal = nonce+pt+tag; not AES-GCM. URL: libsodium AEAD.
- ✓ `kdf_is_argon2id` — asserts the pinned ops/mem equal libsodium `OPSLIMIT_INTERACTIVE` / `ARGON2ID_MEMLIMIT_INTERACTIVE` (real cross-check, not a self-mapping) + Argon2id measurably slower than a fast hash. URL: libsodium pwhash.
- ✓ `raw_key_never_persisted` — `toString` redacted, raw key absent from sealed blob. URL: Android Keystore.

4 instrumented (written, compile; gate pending device unblock):
- ✓ `native_lib_loads` — `CryptoFactory` → `randomBytes` exercises the native binding (would `UnsatisfiedLinkError` on a missing ABI).
- ✓ `argon2id_on_device_deterministic` — same inputs → same key, < 10 s bound, different chat-id → different key.
- ✓ `keystore_wrap_unwrap_roundtrip` — wrap/unwrap equal; on-disk blob does not contain raw key.
- ✓ `native_aead_roundtrip` — real on-device XChaCha20-Poly1305 seal/open + wrong-key reject.

## Plan compliance — Interaction scenarios

- ✓ **Altered 8-byte header on disk → Rejected, no crash** — `header_is_bound_as_aad` + `tampering_codec_id_byte_in_envelope_rejects`.
- ✓ **Cross-client read (same key, codec=0x00) → exact bytes** — seal/open are stateless and key-only; `seal_envelope_..._roundtrips` (JVM) + `native_aead_roundtrip` (device) prove a blob sealed by one instance opens on another with the same key.
- ◑ **Slow Argon2id off the UI thread; key cached so concurrent sends/receives don't re-run it** — off-thread is implemented (`KeySources` runs `argon2id` in `withContext(ioDispatcher = Dispatchers.IO)`). The "cached per chat" property is provided by `ChatKeyStore.store/load` (derive once, load thereafter) rather than an in-memory cache. **No concurrent-state test** exercises a send/receive while a derivation is in flight, and **no test asserts the KDF runs off the main dispatcher** (the dispatcher is injectable but unused by any test). See product note 1.
- ✓ **Wrong passphrase → every open Rejected** — `wrong_key_fails_open` / `wrong_key_on_envelope_rejects` (a wrong-passphrase key is byte-equivalent to any wrong key at the AEAD boundary).

## Stack expectations compliance

- ✓ **Argon2id (libsodium default PHF)** — `PWHASH_ALG_ARGON2ID13`, INTERACTIVE preset (ops 2 / 64 MiB); test cross-checks against the library constants.
- ✓ **XChaCha20-Poly1305 AEAD, 192-bit random nonce** — combined-mode `cryptoAeadXChaCha20Poly1305Ietf{Encrypt,Decrypt}`, 24-byte random nonce; stack-spec test pins the constants.
- ✓ **lazysodium native `.so` per ABI** — `@aar` for lazysodium-android + JNA with DEX-dup excludes; instrumented suite is the mandatory ABI gate (written; pending device unblock).
- ✓ **Argon2id off the UI thread** — `withContext(Dispatchers.IO)` for the KDF.
- ✓ **Keystore directly (security-crypto deprecated, not used)** — `KeyGenParameterSpec` AES/GCM wrap key in `AndroidKeyStore`; no `androidx.security:security-crypto` anywhere.
- ✓ **Never persist raw key/passphrase to disk** — only Keystore-wrapped `iv ‖ ct+tag` written; no disk-credential reference in crypto/keystore.

## Spec fidelity (§5.1)

- ✓ blob = nonce(24) ‖ AEAD ct+tag (`Aead.seal`).
- ✓ 8-byte envelope header bound as AAD; any-byte tamper → Rejected.
- ✓ fresh CSPRNG nonce per seal at blob start.
- ✓ codec-id stays 0x00 (`MessageCrypto` uses `CODEC_NONE`; `CODEC_DEFLATE` is the spec-defined-but-unused reject-path constant, never used to compress).
- ✓ blob < 40 bytes → Rejected (`MIN_BLOB_SIZE` guard), not a bounds error.

## Categorical coverage & Out of scope

- ✓ All three key sources (known/random/passphrase) and both access modes (public/private) implemented — the full set; nothing silently deferred.
- ✓ No out-of-scope leakage: no chat-model/UI, no invite/QR encoding, no directory, no X25519/DH, no compression wired (codec 0x00), no double-layer encryption. Forward references appear only in doc comments.

## Conventions

- ✓ Every source file ≤ 300 lines (max 118: `KeySources.kt`); functions ≤ 50 lines.
- ✓ No file-level suppressions; no `!!` on any crypto/JNA/keystore path.
- ✓ Single typed `OpenResult` (`Opened`/`Rejected`); no exceptions leak from the open path.

## Definition of Done

- [x] All plan scenarios implemented and tested
- [ ] Interaction scenarios have concurrent-state tests — off-thread KDF + cache-via-Keystore are implemented, but the "Argon2id in flight during concurrent send/receive" scenario has no concurrent-state test, and no test asserts the KDF runs off the main dispatcher (product note 1)
- [x] Stack expectations respected; stack-spec tests pass
- [x] Product Contract honored — N/A (no Product Contract touched; backend-only)
- [~] Pipeline green — `./gradlew test` (23 crypto unit tests + transport tests), `ktlintCheck`, `lint` all GREEN. `connectedAndroidTest` PENDING device unblock (MIUI "Install via USB"); the 4 instrumented tests compile and are correctly written; arm64 `.so` verified packaged. Treated as pending-device per the review brief, not a code failure.
- [x] State file updated (`.ai-pm/state/current.md`)
- [x] Product Impact Report — N/A (no contract touched)
- [x] Docs updates landed — `webdav-layout.md` §5/§5.1, `architecture.md` decision 9 + chat taxonomy all present in branch
- [x] Expected artifacts exist — plan, this review; no Product Contract needed (not user-facing)

**DoD: pass** (the single unchecked item is a non-blocking product note — the load-bearing safety property, off-UI-thread derivation, is implemented; what is missing is a concurrent-state *test*, not the behavior)

## Blocking

None.

## Notes (product)

1. **Interaction scenario "slow Argon2id while a send/receive is in flight" has no concurrent-state test, and no test asserts the KDF actually runs off the main dispatcher.** The behavior is implemented (`withContext(Dispatchers.IO)`, dispatcher injectable for tests) and the "cached so it doesn't re-run" property is satisfied by `ChatKeyStore` persistence rather than an in-memory cache. Why it matters: the off-UI-thread guarantee for a memory-hard KDF is the one stack constraint with a user-visible failure mode (UI jank / ANR during password entry), and right now nothing regression-guards it. A cheap fix: a `KeySources` test that injects a tracking dispatcher and asserts the Argon2id call ran on it (and is not re-run when a stored key exists). PM to weigh whether to require it now or defer to the chat-model feature that wires the actual send/receive loop.

2. **The "cached per chat" wording in the plan's Interaction scenarios maps onto Keystore-backed persistence, not an in-memory per-chat cache.** Functionally equivalent for "don't re-run Argon2id," but the substrate itself holds no live cache — a caller that re-derives instead of calling `ChatKeyStore.load` would re-run the KDF. Why it matters: the no-re-derivation guarantee depends on the *caller* (the future chat-model feature) using `load` rather than `keyFromPassphrase` on every send. Worth recording as an expectation on that downstream feature.

## Verdict

approve

<!-- orchestrator appends after code-review pass: -->
## Code review findings

## Code review
