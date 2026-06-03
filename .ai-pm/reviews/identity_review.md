# identity — plan compliance review

Reviewed: 2026-06-03 · Branch `feature/identity` (uncommitted working tree) · read-only.
Baseline: `docs/features/identity_plan.md`, `docs/architecture.md` decision #10 + Security constraints, `docs/stack-notes.md` Crypto "Public-key primitives", `CLAUDE.md`.

## Plan compliance — Scenarios (1–6)

- ✓ **Scenario 1 — stable identity, two distinct keypairs, secret Keystore-wrapped, public exportable.** `IdentityCrypto.generateIdentity()` makes a separate Ed25519 sign keypair + X25519 box keypair (`identity/IdentityCrypto.kt:24-33`). Secret keys Keystore-wrapped device-local under a distinct alias (`identity/IdentityStore.kt`). Public half exportable via `Identity.publicIdentity()` / `PublicIdentity`. Test: `identity_generation_two_distinct_keypairs` (`IdentityCryptoTest.kt:23`), `identity_keystore_wrap_unwrap` (instrumented).
- ✓ **Scenario 2 — key agreement DH→KDF→ChatKey, symmetric, KDF'd not raw.** `agreeChatKey` runs `boxBeforeNm` → keyed BLAKE2b `keyedHash` → `ChatKey` (`IdentityCrypto.kt:44-61`); raw shared secret zeroized, never used as the AEAD key. Test: `dh_derives_same_chatkey_both_sides` (incl. real AEAD round-trip A→B via existing `crypto/Aead`) + `chatkey_is_kdf_of_shared_secret_not_raw`.
- ✓ **Scenario 3 — sealed box, ephemeral sender, recipient-only open.** `seal`/`openSealed` over `crypto_box_seal`/`crypto_box_seal_open` (`IdentityCrypto.kt:69-100`, `LazySodiumCrypto.kt:136-159`). Test: `sealed_box_roundtrip`, `dh_and_seal_native_roundtrip` (instrumented).
- ✓ **Scenario 4 — Ed25519 detached sign/verify, hard reject on -1.** `sign`/`verify` (`IdentityCrypto.kt:103-117`); `signVerifyDetached` returns false on libsodium -1 and on wrong-length inputs (`LazySodiumCrypto.kt:173-181`). Test: `sign_verify_detached_roundtrip`, `ed25519_verify_rejects_on_failure`.
- ✓ **Scenario 5 — symmetric fingerprint over both public keys.** BLAKE2b over the two per-party hashes (each binding signPub‖boxPub) sorted then concatenated (`IdentityCrypto.kt:129-159`). Test: `fingerprint_deterministic_and_symmetric`, `fingerprint_symmetric_on_device` (instrumented).
- ✓ **Scenario 6 — wrong/tampered input is a typed rejection, never a crash.** `SealedResult.Rejected`, `verify`→false, `Identity.deserialize`→null on bad length. Test: `sealed_box_wrong_recipient_rejected` (wrong recipient + tampered + truncated), `signature_tamper_or_wrong_key_rejected`.

## Plan compliance — Test plan

JVM unit (all present, ran in `./gradlew test` — IdentityCryptoTest 8 + IdentityStackSpecTest 5):
- ✓ identity_generation_two_distinct_keypairs
- ✓ dh_derives_same_chatkey_both_sides (with AEAD round-trip)
- ✓ sealed_box_roundtrip
- ✓ sealed_box_wrong_recipient_rejected
- ✓ sign_verify_detached_roundtrip
- ✓ signature_tamper_or_wrong_key_rejected
- ✓ fingerprint_deterministic_and_symmetric
- ✓ identity_persists_across_load — split: JVM half `identity_serialize_roundtrip_preserves_keys` (serialization lossless + null on bad length) + instrumented `identity_persists_across_load` (Keystore generate-once). Faithful: full persistence requires device Keystore.

Interaction scenarios:
- ✓ agree_chatkey_symmetric_cross_device (`IdentityCryptoTest.kt:59`) — both DH paths equal + third-party cannot derive.
- ✓ identity_store_does_not_disturb_chat_keys (instrumented) — stores a real `ChatKey` via `ChatKeyStore`, then asserts identity store/load/remove leaves it intact, and vice versa. Concurrent-state condition genuinely set up (not happy-path only).
- ✓ sealed_wrong_recipient_is_typed_rejection — covered by `sealed_box_wrong_recipient_rejected` + on-device `dh_and_seal_native_roundtrip`.
- ✓ verify_failure_drops_entry — covered by `signature_tamper_or_wrong_key_rejected` + on-device `verify_rejects_tamper_on_device`.

Stack-spec (each cites its source URL, verifies the cited rule):
- ✓ chatkey_is_kdf_of_shared_secret_not_raw — asserts derived ChatKey ≠ raw `boxBeforeNm` output. URL cited.
- ✓ box_nonce_is_24_bytes_and_seal_is_fresh — asserts `Box.NONCEBYTES==24`, `SEALBYTES==48`, overhead exact, two seals of identical plaintext differ (freshness). URL cited. (See note 1.)
- ✓ ed25519_verify_rejects_on_failure — false on all-zero sig, wrong-length sig, wrong-length pk. URL cited.
- ✓ sign_and_dh_keys_are_independent — strong form: asserts boxPub ≠ `convert(signPub)` using the rejected ed25519→curve25519 conversion as the negative oracle. URL cited.

Instrumented (`connectedAndroidTest`, written + compile; gate pending device — see DoD note):
- ✓ native_pubkey_paths_load
- ✓ identity_keystore_wrap_unwrap — also asserts neither raw sk appears in the on-disk wrapped blob (subsequence scan).
- ✓ dh_and_seal_native_roundtrip

## Spec / decision fidelity

- ✓ Two DISTINCT keypairs, NOT the ed25519→curve25519 conversion (independently generated; proven by the negative-oracle stack-spec test).
- ✓ DH shared secret always KDF'd (keyed BLAKE2b, domain-separated context) before the AEAD; raw secret zeroized.
- ✓ Sealed-box typed rejection (`SealedResult.Rejected`); sender-unauthenticated property documented at the API and propagated to architecture decision #10 / Security constraints (rotation MUST also Ed25519-sign).
- ✓ verify returns false on failure (-1 hard reject), no best-effort.
- ✓ Fingerprint symmetric via sorted concatenation; binds both keys per party.
- ✓ Identity secret keys Keystore-wrapped under distinct alias `owdm.identity.wrap.v1` + distinct dir `identity/` (vs `owdm.chatkey.wrap.v1` / `chatkeys/`) — does not disturb chat keys.
- ✓ Public keys exportable.

## Security

- ✓ Identity secret keys never on disk/logs: `Identity.toString()` redacted to `Identity(***)`; secrets reachable only via `copy*Secret()`; only AES/GCM-wrapped blob written; instrumented test asserts raw sk not present in the on-disk blob.
- ✓ DH-derived `ChatKey` consumed by the existing `crypto/Aead` unchanged (same `ChatKey` type, `KEY_BYTES=32`); AEAD round-trip tests pass on both backends. No weakening of the AEAD.

## Reuse not duplication

- ✓ Public-key ops EXTEND the existing `NativeCrypto`/`LazySodiumCrypto` (same interface the AEAD uses); identity logic backend-agnostic across lazysodium-java/-android.
- ✓ `IdentityStore` mirrors `ChatKeyStore`'s Keystore discipline (KeyGenParameterSpec AES/GCM, `iv‖ct+tag` blob, no deprecated EncryptedSharedPreferences) under a distinct alias.
- ✓ Existing crypto + transport suites stay green (`./gradlew test` BUILD SUCCESSFUL).

## Conventions

- ✓ All source files ≤300 lines (largest: `LazySodiumCrypto.kt` 194, `IdentityCrypto.kt` 174).
- ✓ No file-level suppressions in `identity/` or the extended `crypto/` files.
- ✓ Functions within limits; ktlintCheck green.

## Out of scope respected

- ✓ No directory / publishing, no on-disk format defined (pure bytes + key handles).
- ✓ No chat-establishment flow, no rotation wiring, no UI/QR.
- ✓ Both key-agreement AND signing included (per the plan's categorical decision — no primitive silently deferred). Working-tree diff is confined to `identity/`, the planned `crypto/` extensions, and the planned doc updates — no scope creep.

## Definition of Done

- [x] All plan scenarios implemented and tested
- [x] Interaction scenarios have concurrent-state tests (chat-key coexistence sets up the prior-stored-key condition; generate-once guarded)
- [x] Stack expectations respected; stack-spec tests pass (4 cited-rule tests green on JVM)
- [x] Product Contract honored — N/A, no Product Contract touched (backend-only, no user-visible behavior; architecture decision #10 = "pure crypto, no UI")
- [x] Pipeline green — `./gradlew test` + `ktlintCheck` + `lint` all BUILD SUCCESSFUL. `connectedAndroidTest` is the device-gate; tests written + compile, blocked by the MIUI "Install via USB" device-side gate (not a code/ABI failure — same `.so` proven to load in the crypto feature). Treated as "pending device unblock" per review instruction.
- [x] State file updated (`.ai-pm/state/current.md`)
- [x] Product Impact Report — N/A (no contract touched)
- [x] Docs updates landed — architecture decision #10 added, decision #9 follow-on (a) marked IMPLEMENTED, `app/.../identity/` in the module map; stack-notes Crypto public-key sub-section present; CLAUDE.md `connectedAndroidTest` gate intact
- [x] Expected artifacts exist — plan (`docs/features/identity_plan.md`), this review; no contract required (not user-facing)

**DoD: pass**

## Blocking

None.

## Notes (product)

1. `box_nonce_is_24_bytes_and_fresh` is implemented as a constant assertion (`Box.NONCEBYTES==24`) plus a behavioral freshness assertion (two seals of identical plaintext differ). The substrate never exposes a caller-nonce `crypto_box_easy` path — it only uses sealed-box, whose nonce is internal/ephemeral — so the 24-byte-and-fresh rule is exercised at the only place a box nonce appears. Faithful adaptation, no gap; recorded only so the PM knows the "fresh nonce" guarantee here is sealed-box-internal, not a directly-supplied-nonce path. Why it matters: the moment a future feature adds an authenticated `crypto_box_easy` (caller-supplied nonce) path, a direct nonce-freshness test must be added then.

2. The `connectedAndroidTest` validator (gates the native public-key `.so` per ABI + Keystore wrap/unwrap) did not execute this run — blocked by the device's MIUI install gate, not by code. Per review scope this is "pending device unblock," but it remains the one mandated validator not yet green; the security-critical native-load and Keystore-wrap assertions are unverified-on-device until it runs. Why it matters: the PM should ensure the device gate is cleared and `connectedAndroidTest` runs green before this is considered fully shipped, since `UnsatisfiedLinkError` and Keystore behavior are only observable on-device.

## Verdict

approve

<!-- orchestrator appends after code-review pass: -->
## Code review findings

## Code review
