# identity — plan

> The **asymmetric / identity substrate** on top of the done symmetric crypto substrate. Provides per-user identity keypairs + the public-key primitives the community/directory/rotation/private-chat features will consume: key agreement, anonymous sealed encryption, signatures, and a verification fingerprint. It produces/consumes opaque bytes and key handles — it does NOT publish anything, define the directory file format, establish chats, or wire rotation. No UI. Backend/infrastructure.

## Scenarios

System behaviors (no UI yet — the identity primitives consumers build on):

1. On first run a user gets a stable **identity**: an Ed25519 **signing** keypair AND an X25519 **box** keypair (two distinct keypairs per libsodium's recommendation), generated on-device. The **secret** keys are Android Keystore-wrapped, device-local, never on the WebDAV disk; the two **public** keys are exportable for publishing.
2. **Key agreement (remote private chats):** given my X25519 secret key and a peer's X25519 public key, both sides independently derive the **same** symmetric `ChatKey` — a 4th key source feeding the existing XChaCha20-Poly1305 AEAD. The raw Diffie-Hellman output is run through a KDF (never used directly as the AEAD key).
3. **Sealed box (rotation primitive):** a payload can be encrypted to a recipient's X25519 public key with **no sender identity** (ephemeral sender key); only the recipient's keypair opens it. (This is what the future rotation feature uses to encrypt the new disk password per remaining member.)
4. **Signatures (directory-entry authentication):** a message/blob can be Ed25519-signed (detached) with my signing key and verified against my public key; verification failure (`-1`) is a hard reject, never best-effort.
5. **Verification fingerprint (safety number):** a deterministic, **symmetric** fingerprint is computed from two parties' public identity keys — the same value on both devices regardless of which computes it — for out-of-band verification (the human display format is a later UI concern; this feature yields the fingerprint material).
6. Wrong key / tampered input on any path fails as a typed rejection (open fails, verify returns false) — never a crash, never wrong-but-accepted output.

## Existing behaviors this feature touches

`docs/user-journeys.md` is a skeleton. Concrete touch points are code/contract:
- **Crypto substrate** (`crypto/`): the DH-derived `ChatKey` must be a drop-in 4th key source for the existing `Aead.seal/open` (alongside passphrase/random/known). The existing `ChatKey` type / AEAD must not change behavior.
- **Keystore** (`keystore/ChatKeyStore`): the identity secret keys use the same Android-Keystore-wrapping discipline as chat keys (a distinct key alias / store; no conflict). All existing crypto + transport tests stay green.

## Contracts

New, internal:
- **Identity** — `generateIdentity() → Identity` (Ed25519 signing keypair + X25519 box keypair), `loadIdentity()/storeIdentity()` (secret keys Keystore-wrapped), `publicIdentity() → {signPub(32), boxPub(32)}` (exportable). Deterministic persistence: one identity per device, reused across sessions.
- **Key agreement** — `agreeChatKey(myBoxSecret, peerBoxPublic) → ChatKey`: X25519 shared secret (`crypto_box_beforenm`) → KDF → a `ChatKey` consumable by the existing AEAD. Symmetric: A(priv,B.pub) == B(priv,A.pub).
- **Sealed box** — `seal(plaintext, recipientBoxPublic) → sealedBlob` and `openSealed(sealedBlob, myBoxKeypair) → Opened | Rejected`. Anonymous (no sender auth — documented property).
- **Signing** — `sign(message, mySignSecret) → signature(64)` and `verify(signature, message, signerSignPublic) → Boolean` (false on `-1`).
- **Fingerprint** — `fingerprint(pubA, pubB) → bytes`: BLAKE2b over the sorted public identity keys; identical regardless of argument order / device.

## Stack expectations touched

From `docs/stack-notes.md` → Crypto library "Public-key primitives" (Last reviewed 2026-06-03):

- **crypto_box**: "both keys are 32 bytes … Nonce is 24 bytes … Combined-mode API is `crypto_box_easy`/`crypto_box_open_easy`." Source: https://doc.libsodium.org/public-key_cryptography/authenticated_encryption
- **crypto_box nonce**: "it should be used with just one invocation … for a particular pair of public and secret keys" — same nonce both sides, unique per (key,message); 24-byte random has negligible collision risk. Source: https://doc.libsodium.org/public-key_cryptography/authenticated_encryption
- **Do NOT use the raw shared secret as the chat key**: "It is a DH output, not a uniformly-distributed key destined for an AEAD; run it through a KDF / generichash first." Source: https://doc.libsodium.org/public-key_cryptography/authenticated_encryption
- **crypto_box_seal**: "encrypts to a recipient public key only; libsodium generates an ephemeral sender keypair internally … Overhead `crypto_box_SEALBYTES` = 48." Source: https://doc.libsodium.org/public-key_cryptography/sealed_boxes
- **Sealed box is NOT sender-authenticated**: "the recipient … cannot verify the identity of the sender" — if provenance matters, sign separately. Source: https://doc.libsodium.org/public-key_cryptography/sealed_boxes
- **crypto_sign (Ed25519)**: "pk=32 / sk=64 / sig=64 … verify with `crypto_sign_verify_detached` (returns 0 on success, -1 on failure — treat -1 as a hard reject)." Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
- **Distinct sign vs DH keys**: "using distinct keys for signing and for encryption is still highly recommended." → two independent keypairs (decision below). Source: https://doc.libsodium.org/advanced/ed25519-curve25519
- **Safety number**: "use `crypto_generichash` (BLAKE2b) over both parties' public identity keys … sort the two and concatenate … the sort is what guarantees symmetry." Source: https://doc.libsodium.org/hashing/generic_hashing ; https://signal.org/blog/safety-number-updates/
- **Identity secret-key storage**: "same constraint as chat keys: Android Keystore-wrapped in app-private storage, never written to the WebDAV disk." Source: https://developer.android.com/privacy-and-security/keystore
- **lazysodium naming gotcha**: sealed-box methods are `cryptoBoxSealEasy` / `cryptoBoxSealOpenEasy` (not `cryptoBoxSeal`). Source: https://github.com/terl/lazysodium-java/blob/master/src/main/java/com/goterl/lazysodium/interfaces/Box.java
- **Validator**: `./gradlew connectedAndroidTest` gates the native public-key `.so` paths per ABI (same rule as the symmetric native paths). Source: https://github.com/terl/lazysodium-android

## Key design decisions (mine — recorded for the architect/coder)

- **Two independent keypairs** (Ed25519 signing + X25519 box), NOT the single-Ed25519-converted-to-X25519 path — follows libsodium's "distinct keys highly recommended." The **fingerprint is computed over BOTH public keys**, so the user still verifies exactly **one** fingerprint (UX of a single identity is preserved despite two keys).
- **DH → KDF → ChatKey**: `crypto_box_beforenm` shared secret is run through BLAKE2b/`crypto_kdf` to produce the AEAD key — never the raw shared secret.
- **Fingerprint display format** (digit count / grouping) is deferred to the UI feature; this substrate yields the deterministic fingerprint bytes.

## Interaction scenarios

The identity persists in Keystore (shared mechanism with chat keys) and its outputs feed the AEAD; not isolated.

- **Identity is generated once and reused**: a second `loadIdentity()` after first-run generation returns the same keys (persisted), not a fresh identity; concurrent first-run calls must not create two identities (generate-once guard).
- **Two devices derive the same chat key**: device A `agreeChatKey(A.sec, B.pub)` and device B `agreeChatKey(B.sec, A.pub)` produce identical `ChatKey`s → a message A seals with it opens on B via the existing AEAD.
- **Identity store coexists with the chat-key store**: both wrap secrets via Android Keystore under distinct aliases — storing/loading an identity must not disturb stored chat keys, and vice versa.
- **A sealed payload reaches the wrong recipient**: `openSealed` with a non-matching keypair returns `Rejected`, not a crash or partial plaintext.
- **A signature is checked against the wrong signer / tampered message**: `verify` returns false (libsodium `-1`), and the caller drops the entry — no best-effort acceptance.

## Test plan

- Existing tests that must pass: all `crypto` + `webdav-transport` suites stay green.
- New tests (JVM unit — lazysodium-java + system libsodium):
  - `identity_generation_two_distinct_keypairs`: generating an identity yields an Ed25519 (pk32/sk64) and an X25519 (pk32/sk32) keypair that are distinct.
  - `dh_derives_same_chatkey_both_sides`: A(sec, B.pub) and B(sec, A.pub) yield identical ChatKeys; a message sealed with one opens with the other via the existing AEAD.
  - `sealed_box_roundtrip`: seal to a recipient public key, open with that recipient's keypair → original bytes.
  - `sealed_box_wrong_recipient_rejected`: opening a sealed blob with a different keypair → `Rejected`.
  - `sign_verify_detached_roundtrip`: a signature over a message verifies true against the signer's public key.
  - `signature_tamper_or_wrong_key_rejected`: a modified message, or verification against a different public key, returns false.
  - `fingerprint_deterministic_and_symmetric`: `fingerprint(A,B) == fingerprint(B,A)`, stable across calls, and differs for a different key pair.
  - `identity_persists_across_load`: store then load returns the same identity (generate-once).
- Interaction scenario tests:
  - `agree_chatkey_symmetric_cross_device`: simulate two identities, assert both `agreeChatKey` paths produce the same key and an AEAD message round-trips A→B.
  - `identity_store_does_not_disturb_chat_keys`: storing/loading an identity leaves a previously stored chat key intact (and vice versa).
  - `sealed_wrong_recipient_is_typed_rejection`: covered above, asserted as the interaction outcome (no crash).
  - `verify_failure_drops_entry`: a tampered signed blob → verify false → caller-visible rejection.
- Stack-spec tests (verify the cited rule; reference the source URL in a comment):
  - `chatkey_is_kdf_of_shared_secret_not_raw`: the derived ChatKey is NOT equal to the raw `crypto_box_beforenm` output (a KDF was applied) — per the "do not use raw shared secret" rule.
  - `box_nonce_is_24_bytes_and_fresh`: crypto_box uses a 24-byte nonce, freshly generated per message (not reused/fixed).
  - `ed25519_verify_rejects_on_failure`: verify returns false on a bad signature (libsodium `-1` = hard reject), never true-by-default.
  - `sign_and_dh_keys_are_independent`: the Ed25519 and X25519 keypairs are separately generated, not one converted from the other (the chosen distinct-keys design).
- Instrumented tests (`connectedAndroidTest` on device 5c3ff0):
  - `native_pubkey_paths_load`: crypto_box / crypto_sign / sealed-box run on the device ABI with no `UnsatisfiedLinkError`.
  - `identity_keystore_wrap_unwrap`: identity secret keys wrapped via Android Keystore and unwrapped yield the same keys; raw secret not retrievable in plaintext from storage.
  - `dh_and_seal_native_roundtrip`: real lazysodium-android key-agreement + sealed-box round-trip on-device.

## Docs to update

- **`docs/architecture.md`** (owner: `Builder`): record a new decision "Identity substrate (X25519 + Ed25519)" — two distinct keypairs (one identity, fingerprint over both); DH→KDF→ChatKey as the 4th key source; sealed-box as the rotation primitive (with the **sender-unauthenticated** property → the rotation feature MUST Ed25519-sign rotation payloads); identity secret keys Keystore-wrapped. Update decision #9's "follow-on" note so X25519 is recorded as **implemented** (no longer deferred). Add the `app/.../identity/` package to the File-layout module map (planned→implemented once code lands).
- **`AGENTS.md`** Pipeline: `connectedAndroidTest` continues as the active device gate (now also covering public-key native paths).

## Out of scope

- **The directory** (publishing public keys / signed entries on the disk) and its on-disk file format — separate feature. This substrate produces public-key bytes + signatures; the directory feature defines how they are stored in `docs/protocol/webdav-layout.md`.
- **Chat-establishment flow** (deciding to start a remote private chat, fetching a peer's public key, wiring `agreeChatKey` into a real chat) — separate feature.
- **Rotation mechanism wiring** (creating the per-member sealed payloads, the new-password flow) — separate feature. This substrate provides `seal` + `sign`; the rotation feature combines them (and MUST sign payloads — sealed-box alone is sender-unauthenticated).
- **UI / QR** — identity verification screen, the human fingerprint display format (digit count/grouping), peer-key scanning — separate feature.
- **Sibling of the "identity primitive" categorical** — both key-agreement (X25519) AND signing (Ed25519) ARE included here (PM decision, the full identity); no primitive deferred at the substrate level.
- **Forward secrecy / double ratchet** — long-term identity keys only here; per-message ratcheting remains the deferred stronger path.
