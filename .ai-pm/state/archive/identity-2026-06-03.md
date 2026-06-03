# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

identity — asymmetric/identity substrate: per-user Ed25519+X25519 keypairs (Keystore-wrapped), X25519 DH→KDF→ChatKey (4th key source), sealed-box, Ed25519 sign/verify, safety-number fingerprint. Builds on crypto substrate. Backend-only.

## Status

coding — Pass-2 review durability/concurrency fixes applied; ALL 4 gates GREEN (incl. connectedDebugAndroidTest on 5c3ff0: 18/18, 0 failures). Ready for re-review. NOT committed (instructed).

## Done

- Plan approved, saved to docs/features/identity_plan.md
- pm-stack-researcher extended stack-notes Crypto with public-key primitives (crypto_box / sealed-box / crypto_sign / conversion / fingerprint)
- Product map updated (identity in Infrastructure bucket); branch feature/identity
- pm-architect recorded decision #10 (two keypairs, DH→KDF→ChatKey, sealed-box rotation primitive w/ sender-unauth note, fingerprint), updated decision #9 follow-on (a)=IMPLEMENTED, added app/.../identity/ to module map
- pm-coder: implemented identity/ substrate + extended crypto NativeCrypto/LazySodiumCrypto with public-key ops
  - NativeCrypto + LazySodiumCrypto: added boxKeypair / signKeypair / boxBeforeNm / boxSeal / boxSealOpen / signDetached / signVerifyDetached / keyedHash (native-mode byte[] calls; BoxKeyPair / SignKeyPair value types)
  - identity/Identity (two keypairs, redacted, serialize/deserialize 160B), PublicIdentity (signPub+boxPub), SealedResult (Opened/Rejected typed), IdentityCrypto (generateIdentity / agreeChatKey DH→KDF→ChatKey / seal / openSealed / sign / verify / fingerprint), IdentityStore (Keystore-wrapped, distinct alias owdm.identity.wrap.v1 + distinct dir, generate-once guard), IdentityFactory (Android entry point)
  - JVM tests (lazysodium-java): IdentityCryptoTest (generation/DH/AEAD-roundtrip/sealed/sign/verify/fingerprint + interaction), IdentityStackSpecTest (chatkey-is-kdf-not-raw / box-nonce-24-and-fresh / ed25519-verify-rejects / sign-dh-keys-independent via convert / serialize-roundtrip)
  - Instrumented tests written (NOT yet run on device — see Remaining): IdentityInstrumentedTest (native_pubkey_paths_load / dh_and_seal_native_roundtrip / fingerprint_symmetric / verify_rejects_tamper), IdentityStoreInstrumentedTest (identity_keystore_wrap_unwrap / identity_persists_across_load / identity_store_does_not_disturb_chat_keys)
- Gates green: ./gradlew test ✓, ./gradlew ktlintCheck ✓, ./gradlew lint ✓
- pm-coder Pass-2 review fixes (durability/concurrency/error-handling in IdentityStore — silent identity-loss class):
  - FIX1 cross-process generate-once: loadOrCreate now takes a cross-process FileLock (RandomAccessFile.channel.lock() on identity/identity.lock) + double-checks load() under the lock before generating; in-process synchronized(generateOnceLock) kept as the fast path. A WorkManager/foreground process in a separate android:process can no longer generate a second identity.
  - FIX2 atomic write: store() now writes via temp file in the same dir → flush+fd.sync() → renameTo (atomic same-fs swap). A crash mid-write leaves the old intact file or the new one, never a partial/zero-length blob.
  - FIX3 typed load: load() returns IdentityLoadResult { None | Loaded | Unrecoverable }. cipher.doFinal / Keystore-invalidation exceptions (AEADBadTagException / KeyPermanentlyInvalidatedException) are caught in KeystoreWrapper.unwrap and mapped to Unrecoverable — never escape, never crash on startup. loadOrCreate generates ONLY on None; on Unrecoverable it throws IdentityUnrecoverableException (surfaced to caller) and NEVER silently regenerates (= no silent account loss).
  - FIX4 keygen zeroize: IdentityCrypto.generateIdentity wipes the signKeypair()/boxKeypair() source arrays (sk + pk) after Identity defensively copies them.
  - CLEANUP5 shared seam: new keystore/KeystoreWrapper(alias, file) owns wrap/unwrap mechanics (AES/GCM-256 Keystore key, iv‖ct+tag, atomic write fix2, typed UnwrapResult fix3). BOTH ChatKeyStore and IdentityStore refactored onto it. Policy stays per-store: ChatKeyStore treats Unrecoverable as absent (chat key re-derivable); IdentityStore surfaces it (account loss). compareLex left as-is (CLEANUP6).
  - Tests: JVM IdentityCryptoTest.generate_identity_zeroizes_keygen_source_arrays (CapturingKeypairNative decorator). Instrumented IdentityStoreInstrumentedTest +4: atomic_write_partial_blob_surfaces_unrecoverable, corrupt_blob_load_returns_unrecoverable_not_none, atomic_write_no_partial_after_store, concurrent_load_or_create_yields_one_identity. identity_store_does_not_disturb_chat_keys still green after the refactor.
- ALL 4 gates GREEN: test ✓ / ktlintCheck ✓ / lint ✓ / connectedDebugAndroidTest on 5c3ff0 ✓ (18 tests, 0 failures, 0 errors — MIUI install gate was open this run).

## Remaining

- pm-plan-checker / code-review re-review of the Pass-2 fixes → commit → merge (pm-coder did NOT commit, per instruction).

## Touched files

- app/.../crypto/NativeCrypto.kt (extended: public-key ops + BoxKeyPair/SignKeyPair)
- app/.../crypto/LazySodiumCrypto.kt (extended: public-key impls)
- app/.../identity/Identity.kt (new)
- app/.../identity/PublicIdentity.kt (new)
- app/.../identity/SealedResult.kt (new)
- app/.../identity/IdentityCrypto.kt (new)
- app/.../identity/IdentityStore.kt (Pass-2: refactored onto KeystoreWrapper; typed load; cross-process FileLock generate-once; atomic write via wrapper)
- app/.../identity/IdentityLoadResult.kt (new, Pass-2: None/Loaded/Unrecoverable + IdentityUnrecoverableException)
- app/.../identity/IdentityFactory.kt (new)
- app/.../keystore/KeystoreWrapper.kt (new, Pass-2: shared wrap/unwrap mechanics + atomic write + UnwrapResult)
- app/.../keystore/ChatKeyStore.kt (Pass-2: refactored onto KeystoreWrapper; Unrecoverable→null policy)
- app/src/test/.../identity/IdentityTestSupport.kt (new)
- app/src/test/.../identity/IdentityCryptoTest.kt (new)
- app/src/test/.../identity/IdentityStackSpecTest.kt (new)
- app/src/androidTest/.../identity/IdentityInstrumentedTest.kt (new)
- app/src/androidTest/.../identity/IdentityStoreInstrumentedTest.kt (new)

## Next step

pm-plan-checker / code-review re-review of the Pass-2 durability/concurrency fixes, then commit + merge.

## Validation

ALL 4 gates GREEN. JVM: ./gradlew test (lazysodium-java) — GREEN (incl. keygen-zeroize test). Device: ./gradlew connectedDebugAndroidTest on 5c3ff0 — GREEN, 18 tests / 0 failures / 0 errors (atomic-write, corrupt-blob-unrecoverable, concurrent-create, no-disturb-chat-keys all pass). Plus lint/ktlintCheck — GREEN.

## Notes

Key decisions: two distinct keypairs (Ed25519 sign + X25519 box) per libsodium recommendation, fingerprint over BOTH pubkeys (user verifies one); DH shared secret always via KDF (keyed BLAKE2b, never raw to AEAD); fingerprint display format = UI feature; rotation feature MUST Ed25519-sign sealed payloads (sealed-box is sender-unauthenticated). Implementation uses Box.Native/Sign.Native/GenericHash.Native byte[] calls (NOT the String-based *Easy Lazy methods, which would corrupt binary bytes via charset conversion) — so native names are cryptoBoxSeal/cryptoBoxSealOpen, the stack-notes Easy-naming gotcha applies only to the Lazy API. IdentityStore reuses ChatKeyStore's Keystore AES/GCM discipline under a distinct alias (owdm.identity.wrap.v1) + distinct dir (identity/) — proven non-disturbing of chat keys by instrumented test. Device 5c3ff0 (MIUI) re-gates "Install via USB" — needs toggle + on-screen confirm during connectedAndroidTest.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` and reset this one to a new task or to "Status: idle".
