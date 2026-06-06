# x25519-identity (remote private chats + D10 fix) — plan

Source: PM feature selection 2026-06-06 (next-step choice "X25519 + fix D10"); scope settled with PM to "D10 fix + remote private chats". Backlog: "X25519 identity (NEAR-TERM)" + the D10 blocker in `.ai-pm/backlog.md` "Feature blockers recorded from quality sweep".

**Scope reality (settled with PM).** Most of the X25519 bundle is already built: the `identity` substrate already ships all primitives (`generateIdentity`, `agreeChatKey` DH→KDF, sealed-box, `sign`/`verify`, `fingerprint`), and the `directory` substrate already publishes + Ed25519-signs each member's two public keys and returns verified peers. So this slice is narrow: **(1) fix blocker D10** so a DH-derived chat key is distinct per chat-id, and **(2) wire the DH-derived key into a usable backend path** — derive a remote chat's key from a directory-discovered peer + a chat-id and store it, so a member can have a private chat with a peer they already share a disk with, **no secret exchanged over any channel**. QR safety-number verification (the fingerprint bytes exist) is **out of scope** — it is inherently visual and belongs to the UI feature. Backend substrate, no UI (same classification as `directory`/`chat-directory`).

## Scenarios

1. **The engine derives a distinct chat key per chat-id from a peer's published public key.** Given the local identity's X25519 box secret and a peer's box public key (as returned by the directory) plus a chat-id, the engine derives a 32-byte `ChatKey` via Diffie-Hellman + a chat-id-bound KDF — never feeding the raw DH output to the AEAD. The two members of the chat derive the **same** key from their own secret + the other's public key (symmetry), so they can talk with no shared secret ever sent over a channel.
2. **Two distinct chats between the SAME pair of members derive DISTINCT keys (D10 fix).** The chat-id is bound into the derivation, so chat A and chat B between the same identity pair get different keys; a message sealed under chat A's key is `Rejected` when opened with chat B's key. (Today's `agreeChatKey` derives one identical key for all chats between a pair — the blocker this fixes.)
3. **A derived remote-chat key is stored and loadable by chat-id like any other chat key.** After provisioning, the key is Keystore-wrapped under its chat-id and `ChatKeyStore.load(chat-id)` returns it, so the existing send/receive path (which already takes a `ChatKey`) drives a remote private chat with no new key-handling path.

## Existing behaviors this feature touches

(from `docs/user-journeys.md`, `docs/architecture.md` decisions 9/10/12, and the identity/crypto/directory reviews — what must not break)

- **The four key sources feeding the single AEAD (decision 9).** DH is the fourth source; this feature makes the DH source correctly per-chat without changing the AEAD layer or the other three sources (passphrase / random / known).
- **`identity` substrate primitives (decision 10).** `agreeChatKey` DH symmetry, `sign`/`verify`, sealed-box, and `fingerprint` must all stay behaviour-identical; their existing tests must pass unchanged.
- **`directory` peer discovery (decision 12).** `DirectoryService.readDirectory` returns verified peers (display name + signing + box public keys); this feature consumes that output, it does not change the directory.
- **`ChatKeyStore` store/load/has/remove (decision 9 / keystore).** Consumed as-is to persist the derived key under the chat-id; the Keystore-wrap discipline (SC4) is unchanged.
- **Secret-zeroization discipline.** DH shared secret + derived intermediates are zeroized after use, as the existing `agreeChatKey` already does.

## Contracts

(new / changed APIs; the bare `agreeChatKey` signature stays stable so its existing tests are untouched)

- **`deriveRemoteChatKey(myBoxSecret, peerBoxPublic, chatId) → ChatKey`** (new) — the production derivation: `crypto_box_beforenm` DH, then a keyed-BLAKE2b KDF whose **domain-separation context includes the chat-id** (a new context version, e.g. `owdm/x25519-chatkey/v2`), producing a 32-byte key distinct per chat-id. Raw DH output never used directly (libsodium rule). Zeroizes shared secret + intermediates. Symmetric across the two parties for a fixed chat-id.
- **Remote-private-chat key provisioning seam** (new) — given the local identity (its box secret) + a peer's public identity (box public, as discovered in the directory) + a chat-id → derive via `deriveRemoteChatKey` → `ChatKeyStore.store(chat-id, key)`. Returns a typed success/failure (so a native crypto failure degrades to a typed result, consistent with the C8 posture — no uncaught throw on the provisioning path). Exact name/home is the coder's choice.
- **`agreeChatKey` (existing, 2-arg) — unchanged signature, KDoc updated** to mark it the bare pairwise DH primitive that is **superseded for chat-key use by `deriveRemoteChatKey`**: its output must not be used directly as a chat key (it is not chat-id-bound). Kept as a tested primitive; no production caller uses it directly after this feature.
- **`importRawKey` / `openSealed` raw-overload secret-wipe consistency** (deferred D10 sibling) — settle the wipe inconsistency **only additively**: wipe internal copies / match the sibling's wipe contract **without changing any caller-observable behaviour an existing test asserts**. Any wipe that would change observable behaviour a current test relies on is deferred and noted, not forced.

## Stack expectations touched

(from `docs/stack-notes.md` → Crypto "Public-key primitives")

- **libsodium key agreement + KDF**: "the raw shared secret is NEVER used directly as the AEAD key — it is a DH output, not a uniformly-distributed key" → run through a KDF. Source: <https://doc.libsodium.org/public-key_cryptography/authenticated_encryption>. `deriveRemoteChatKey` must keep this (DH → keyed-hash KDF), and additionally bind the chat-id into the KDF input for per-chat separation.
- **keyed BLAKE2b / generichash domain separation**: a distinct, versioned context per derivation purpose (the existing code uses `owdm/x25519-chatkey/v1`, `owdm/identity-fp/*`). Source: <https://doc.libsodium.org/hashing/generic_hashing>. The per-chat binding must use a **new versioned context** (v2) so the change is explicit and the chat-id is part of the hashed input.

## Interaction scenarios

- **When a remote-chat key is provisioned while a poll cycle / send is in flight for another chat:** isolated — derivation is pure local crypto, the store is keyed by chat-id (no collision with passphrase/random/known keys already stored under other chat-ids). The new key only becomes usable for the chat once stored; in-flight operations for other chats are unaffected.
- **When two members provision the same chat concurrently (each from their own device):** both derive the **same** key (DH symmetry + same chat-id), so the chat key matches regardless of who provisions first; re-provisioning is idempotent (same inputs → same key → same stored blob).
- **When the peer's box public key is read from the directory but the directory entry is later superseded:** provisioning uses whatever verified key the directory returned at call time; a later directory re-publish does not retro-change an already-derived/stored chat key (the stored key is the source of truth for that chat, like every other chat key).

## Test plan

- **Existing tests that must pass: ALL existing tests, unchanged** — in particular the `agreeChatKey` DH-symmetry tests (signature unchanged), the directory, crypto, keystore, and message suites. Zero existing-test edits (the D10 fix is additive: a new `deriveRemoteChatKey` + provisioning seam, not a signature change to the tested primitive).
- **New tests:**
  - `deriveRemoteChatKey distinct per chat-id (D10 regression)`: same identity pair, two different chat-ids → two **different** `ChatKey`s. given A's secret + B's box-public / when derived for chatId="x" vs chatId="y" / then the two keys differ. This is the core blocker-fix test.
  - `deriveRemoteChatKey symmetric for a fixed chat-id`: A(aSecret, bBoxPublic, chatId) == B(bSecret, aBoxPublic, chatId). given both members / when each derives for the same chat-id / then identical key bytes.
  - `deriveRemoteChatKey never equals the raw DH/pairwise output`: the per-chat key differs from the bare `agreeChatKey` pairwise key (proves the chat-id binding actually changes the output). given the same pair / when comparing deriveRemoteChatKey(...,chatId) to agreeChatKey(...) / then different.
  - `cross-chat isolation end-to-end`: seal a message under chat A's derived key, attempt open under chat B's derived key (same pair) → `OpenResult.Rejected`. given two remote-chat keys for the same pair / when a chat-A ciphertext is opened with the chat-B key / then Rejected.
  - `provisioning stores a loadable key (wiring parity)`: drive the **same provisioning entry point** production uses — peer (from a fake/seeded directory result) + chat-id → provision → `ChatKeyStore.load(chat-id)` returns the same key the derivation produced (not a hand-rolled store call). given a discovered peer / when provisioned / then the store returns the derived key under that chat-id.
  - `provisioning degrades on native crypto failure`: with a fake `NativeCrypto` that fails the DH/KDF, provisioning returns its typed failure (no uncaught throw), consistent with the C8 posture. given a failing native / when provisioning / then a typed failure, not a crash.
- **Interaction scenario tests:**
  - `provision idempotent / concurrent-safe`: provisioning the same (peer, chat-id) twice yields the same stored key; provisioning chat-id "x" does not disturb an already-stored key for chat-id "y". sets up a pre-stored key for another chat / verifies it is untouched and the new one is stored.
- **Stack-spec tests:**
  - `derivation does not use raw DH output as the key`: assert the derived key ≠ the raw `boxBeforeNm` shared secret bytes (the KDF is actually applied). Comment cites <https://doc.libsodium.org/public-key_cryptography/authenticated_encryption>.
  - `per-chat KDF context binds chat-id`: assert the v2 derivation's output changes with chat-id (the chat-id is part of the hashed input), distinguishing it from the v1 fixed-context behaviour. Comment cites <https://doc.libsodium.org/hashing/generic_hashing>.

## Docs to update

- `docs/architecture.md`: extend **decision 10** (or a sub-note) — the DH→ChatKey fourth-source derivation now **binds the chat-id** (v2 KDF context) so each chat between an identity pair gets a distinct key; record the **remote-private-chat key provisioning seam** (directory peer + chat-id → derive → store) as the path that wires the fourth key source into production. Note `agreeChatKey` is retained as the bare pairwise primitive, superseded-for-chat-keys by `deriveRemoteChatKey`. Updated by `pm-architect` post-coding.
- `docs/threat-model.md`: the **D10 fix closes a per-chat key-isolation weakness** — update the identity/DH-key threat coverage (A2/T-row family) to record that DH-derived chat keys are now per-chat (two chats between a pair no longer share a key); confirm no SC is weakened; bump `Last reviewed` to 2026-06-06. Security-relevant surface (key derivation) → required. Updated by `pm-architect` post-coding.
- `docs/user-journeys.md`: add a short note (not a full screen-level journey — no UI yet) that the engine now supports establishing a **remote private chat** with a directory-discovered peer using public keys alone (no passphrase / no secret exchanged), the member-observable capability this enables, surfaced later by the chat/UI feature. Updated by `pm-architect` post-coding.

## Out of scope

- **QR safety-number / fingerprint verification UI** — the `fingerprint` bytes already exist in the identity substrate, but the verification flow is inherently visual (scan + compare + mark-verified) and has no surface today. Deferred to the **UI feature**. Different downstream behaviour (user-facing screen), its own plan.
- **Changing `agreeChatKey`'s signature** — kept stable so its existing DH-symmetry tests stay green; the chat-id binding lives in the new `deriveRemoteChatKey` instead. (A later hardening could make the bare pairwise output a distinct type for compile-time misuse-prevention; that would touch tests, so it is a separate follow-up.)
- **rotate-with-auto-replace / member removal** — uses sealed-box + Ed25519-signed rotation payloads; a separate later feature (this slice is its prerequisite, not its delivery).
- **Host-attested directory entries** — rejected for MVP (PM trust decision); unchanged here.
- **The chat-creation / chat-list / invite surfaces** that would let a member *choose* a peer and a chat-id — those are UI / invite features; this slice provides the backend key-provisioning seam they will call.
- **Any UI** — backend substrate only.
- **importRawKey / openSealed wipe items beyond the additive settlement above** — anything requiring a caller-observable change (and thus a test edit) stays backlogged.
