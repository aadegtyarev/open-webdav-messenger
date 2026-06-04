# directory — design notes

## Context

The `directory` feature (`docs/features/directory_plan.md`) adds the **community user directory** to the shared WebDAV disk: each member publishes a signed, community-key-AEAD-sealed identity entry (display-name + their two public identity keys); any member lists, decrypts, verifies, and resolves the latest entry per member to discover peers and obtain their verified public keys. Backend only — no UI. It consumes the done `identity` substrate (decision 10: `publicIdentity()`, `sign`/`verify`) and `crypto` substrate (decision 9: AEAD `seal`/`open` with a fourth symmetric key — the **community key**), and reuses the `transport/` verbs unchanged. It authors a new section in `docs/protocol/webdav-layout.md` and must not disturb §1–§9.

The structural choice exists because the directory is a **new axis of extension on the disk layout**: §1–§9 are written around a single **chat-root** = one shared folder for one chat's `log/` + `changes/`, whereas the directory is **community-scoped** — one community shares a disk across many chats, and the directory lists the *community's* members, not any one chat's roster. There is no existing community-scoped on-disk collection; the directory introduces the first. Four sub-decisions need pinning before the coder authors the spec section: (1) the on-disk home of the collection, (2) entry serialization/framing, (3) supersede/ordering of updated entries, (4) whether a local Room cache lands now.

This is a genuine new axis (community scope vs. chat scope) with multiple plausible homes and framing choices — a design note is warranted rather than a "just add it" exit.

## Adjacent implementations

1. **`protocol/ChatPaths` + `protocol/HashTag` + `protocol/ChangeEntry`** at `app/src/main/kotlin/org/openwebdav/messenger/protocol/` — pure, stateless, deterministic path/name minting for the §1/§9 layout. `ChatPaths` mints chat-root-relative `log/` and `changes/<member-index-id>/` paths; `HashTag` is the `b32lower(SHA-256(...))[0:N]` filename-safe hashing primitive that both `member-index-id` (§1.2) and `chat-tag` (§9.2) already share; `ChangeEntry` mints the §9.2 `chat-tag "~" order-token` name and resolves max-coordinate-per-tag. The directory's collection path + entry-name minting is the **same kind of job** — a new pure path/name minter alongside these. It dispatches by *string construction*, no I/O.

2. **`protocol/Envelope` + `protocol/MessageId` + `protocol/Base32`** at `…/protocol/` — `Envelope` encodes/decodes the §5 outer frame (`magic ‖ envelope-version ‖ codec-id ‖ flags ‖ reserved ‖ ciphertext-blob`); `MessageId` mints the §2 content-addressed `order-token "~" content-hash` name over the final envelope bytes; `Base32` is the shared RFC 4648 codec. A directory entry that reuses the §5 envelope frame (option 2A below) reuses `Envelope` **verbatim** and reuses `MessageId`'s content-hash-over-final-bytes discipline for the entry's content-addressed name.

3. **`message/MessageSerializer` + `message/MessageParser` + `message/TlvFields`** at `…/message/` — the §8 inner-plaintext layer: a versioned `msg-format-version ‖ kind ‖ sender-id-pubkey(32) ‖ field-count ‖ TLV-fields ‖ signature(64)`, with `signAndSerialize` (Ed25519 detached over `plaintext[0..len-64)`) and a `parse` that returns a **typed `Rejected`** (never throws) on bad signature / malformed / unknown version|kind / out-of-range value. The directory entry's inner signed payload is **structurally the same shape** — a versioned record of fixed public-key fields + a trailing Ed25519 signature over the preceding bytes — so the binary-TLV option (3 below) is symmetric with this existing, audited-shape parser rather than a new idiom.

4. **`crypto/MessageCrypto` + `crypto/KeySources` + `keystore/ChatKeyStore`** + **`identity/IdentityCrypto`** — the seal/open + key + sign/verify pipeline. The directory calls `MessageCrypto.seal/open` with the **community key** (a `ChatKey` from the same family as `known`/`random`, decision 9), `IdentityCrypto.sign`/`verify` (Ed25519 detached, hard-reject on `-1`), and consumes `publicIdentity()`'s two 32-byte public keys. These are **consumed, not extended**; the community key is loaded once per read/publish (no per-entry re-derivation — same discipline as the sync per-cycle key load).

## Behavioral risks in this area

The directory is **not** timer/event-driven (unlike `sync`): publish and read are called synchronously by a future consumer. There is **no poll loop, no cursor, no WorkManager** here, so the §9.3 cursor-advance hazard does **not** apply. The risks are narrower:

- **Map of triggers → effects:** `publishEntry` → one (or, on supersede, one new) AEAD-sealed content-addressed `PUT` into the community directory collection + an idempotent `MKCOL` of the collection. `readDirectory` → one `PROPFIND Depth: 1` + per-entry `GET`/open/verify, writing **nothing** to disk (and, if a cache lands, writing **Room only**). A read never writes to disk, so a read can never trigger another member's publish — **no disk-write feedback loop**.
- **The directory shares the disk with the per-chat `log/`/`changes/` under the one shared credential.** The only cross-feature hazard is **collision of on-disk namespaces**: the directory collection must sit at a path that can never be mistaken for, or collide with, a chat-root's `meta/`/`log/`/`changes/` (resolved by sub-decision 1). A directory read/write must touch no chat folder and vice versa (plan interaction `directory_read_does_not_touch_chat_folders`).
- **Flat trust (SC11):** any member can delete or tamper any entry. A tampered entry must fail AEAD-open or `verify` as a typed rejection and be **dropped without wedging the read** of the remaining valid entries (plan `tampered_entry_does_not_wedge_directory`). This is the same reject-don't-guess + flat-trust-degradation discipline as §3, applied per entry — one bad entry is skipped, never fatal. There is no "cursor" to wedge, so the no-wedge property is simply "iterate, drop-on-reject, continue".
- **Supersede is a pure read-time resolution**, not a write-time mutation (entries are append-only, §3): two versions of one member's entry coexist on disk; the reader picks the latest by a **signed** coordinate (sub-decision 3). The risk is choosing an *unsigned* or *wall-clock* coordinate that an attacker (or a lying clock) could use to make a stale entry win — explicitly guarded below.

---

## Question 1 — On-disk home of the community directory

### The tension

§1 is chat-scoped: a **chat-root** is the folder one credential is scoped to, holding exactly `meta/` + `log/` + `changes/`. The directory is **community-scoped** — it lists the community's members independent of any single chat. Under Topology A (decision 2) there is still only **one credential**, and the backlog's two-layer model says *community membership = holding the disk app-password (one disk per community)*; *chat membership = holding a chat-id + key (many chats per disk)*. So the disk **is** the community boundary: the one credential reaches the whole community's space, within which multiple chat-roots live. The future `meta/community.json` owner marker (community feature — **not designed here**) will also be community-scoped, so the directory needs a clean community-scoped home that leaves room for that sibling without designing it.

### Options

**Option 1A — a community-root with a reserved `directory/` collection, supplied as config (RECOMMENDED).** The community has a **community-root** path on the disk (supplied in config exactly as the chat-root is, §1.1 — base URL + app-password + community-root path, all local config, never written to disk). Directly under the community-root lives a reserved **`directory/`** collection holding one entry file per member-version. Chat-roots live **under the same community-root** (each chat its own sub-collection), but the directory neither reads nor writes them. The future `meta/community.json` owner marker is reserved as a community-root sibling — named-but-not-designed, exactly as §1.3 reserves `meta/chat.json`.

- Where: a new community-scoped section in `webdav-layout.md` (proposed **§10**), with the directory at `<community-root>/directory/`.
- Relation to adjacent: **symmetric** with the §1 chat-root pattern (a reserved collection under a config-supplied root, ensured with idempotent `MKCOL`, listed with `PROPFIND Depth: 1`), lifted one scope level up (community-root ⊃ chat-roots + community collections).
- Pros: one obvious community-scoped home; leaves a clean seam for `meta/community.json` (a sibling of `directory/`) without designing it; mirrors how `sync` takes connection-config + roster out-of-band (here: community key + community-root path out-of-band); the directory's path is `<community-root>/directory/`, never collides with any `<community-root>/<chat>/log|changes|meta`.
- Cons: introduces a second config-supplied path (community-root) alongside the existing chat-root; needs one sentence stating chat-roots are nested under (or peers of) the community-root so a reader knows the directory path can never alias a chat folder.
- Risks: low. The only care is the **collision rule**: the spec must state that `directory/` (and the reserved community-level `meta/`) are reserved community-root segments distinct from any chat-root segment.

**Option 1B — directory as a reserved sub-collection of a chat-root (e.g. the community chat's `meta/`).** The backlog says onboarding lands a member in a mandatory all-members **community chat**; put the directory inside that chat-root's `meta/` (e.g. `<community-chat-root>/meta/directory/`).

- Pros: no new config path — reuses the community chat's existing chat-root.
- Cons: **conflates community scope with one chat's scope** — the directory would live inside a folder whose `meta/`/`log/`/`changes/` are chat-scoped, blurring the very boundary the two-layer model separates; couples the directory's existence to that one chat-root's layout; the future `meta/community.json` owner marker would be forced into a chat-root too, which is wrong (it is community-, not chat-, scoped); a chat-level pruning/retention feature operating on that chat-root could accidentally reach the directory. **Asymmetric** to the clean two-layer model.
- Risks: scope-blur becomes load-bearing once the community feature lands — hard to unwind.

**Option 1C — directory at the disk root, no explicit community-root concept.** Put `directory/` directly at the WebDAV base URL root.

- Pros: simplest possible path.
- Cons: assumes one community == one whole disk with nothing else at the root, which the spec has not committed to (a disk could, in principle, host more than the messenger's tree); gives no named home for `meta/community.json`; less explicit than naming a community-root, so the collision/ownership story is implicit rather than stated.

### Recommendation — Option 1A

A config-supplied **community-root** with a reserved **`directory/`** collection. It is the only option that keeps the **two-layer model honest** (community scope is a distinct, explicitly-named level above chat-roots), mirrors the §1.1 chat-root pattern the codebase already implements (symmetric, lowest surface), and reserves a clean sibling seam for the deferred `meta/community.json` owner marker without designing it. The community key + community-root path are supplied **out-of-band / as config** — the same deferral pattern `sync` uses for connection-config + roster, and consistent with the plan's Scenario 5. **Spec precisely (for the coder authoring §10):**

- **Collection:** `<community-root>/directory/` — a single flat collection of entry files. Ensured with idempotent `MKCOL` (§6), listed with one `PROPFIND Depth: 1` (§6, SC-cited). Community-root path is local config, never written to disk (§1.1 discipline, SC3/SC4 family).
- **Reserved siblings (named, not designed):** `<community-root>/meta/community.json` (owner marker, **community feature**) and the per-chat chat-roots are reserved community-root children; the directory feature touches **only** `directory/`. State the collision rule: `directory/` and the community-level `meta/` are reserved community-root segments, distinct from any chat-root.
- **One credential (Topology A):** the directory is in the same shared space under the same app-password — **not** an access-control boundary (SC11). Confidentiality comes from the community-key AEAD seal, not from the path.

---

## Question 2 & 3 are coupled — entry framing and supersede

(Framing first because the supersede coordinate lives **inside** the signed payload the framing defines.)

## Question 2 — Entry serialization / framing

Each entry's inner **signed payload** = display-name + Ed25519 signing public key (32) + X25519 box public key (32) + (supersede coordinate, sub-decision 3) + a 64-byte Ed25519 detached signature over the signed payload; the **whole entry** is then AEAD-sealed with the community key. The choice is how to serialize the inner payload, and what outer frame wraps the ciphertext.

### Options

**Option 2A (outer) — reuse the §5/§5.1 envelope frame verbatim (RECOMMENDED for the outer wrap).** The on-disk entry blob is exactly the §5 frame: `magic "OWDM" ‖ envelope-version 0x01 ‖ codec-id 0x00 ‖ flags 0x00 ‖ reserved 0x00 ‖ ciphertext-blob`, where `ciphertext-blob = nonce(24) ‖ XChaCha20-Poly1305(community-key, header-as-AAD, inner-signed-payload)`. Reuses `protocol/Envelope` and `crypto/MessageCrypto.seal/open` unchanged; the 8-byte header is AAD (tamper-evident); a 24-byte fresh random nonce per seal (plan `entry_aead_uses_24_byte_random_nonce`); a blob `< nonce(24)+tag(16)=40` bytes is a reject/not-ready path (§5.1).

- Relation to adjacent: **symmetric** with every message file on disk — an entry is "an OWDM envelope whose plaintext happens to be a directory record rather than a chat message". One frame parser, one AEAD path, one reject discipline across the whole disk.
- Pros: zero new framing code or dependency; the entry is indistinguishable on the wire from a message envelope to a non-keyholder (uniform metadata surface); `codec-id` reserves future compression for free; the reject-don't-guess + truncated-blob guards already exist.
- Cons: the entry is not a chat message, so the `codec-id`/`flags` bytes are slightly more frame than strictly needed — negligible (8 bytes).

**Inner payload — Option 3-bin (binary versioned TLV, RECOMMENDED) vs Option 3-json (JSON).**

- **Binary versioned TLV (RECOMMENDED):** a `dir-entry-version(1)` prefix + fixed/TLV fields + trailing Ed25519 signature, in the **same shape as §8** (`message/MessageSerializer`). Symmetric with the existing inner-message parser the codebase already ships and tests; no dependency; an entry is "structured-record-like and lands inside AEAD just like a message" (exactly the plan's framing). Reject-don't-guess on unknown version, bad length prefix, out-of-range, or bad signature — the same typed-rejection discipline as `MessageParser`. **Pin the inner field order** (below).
- **JSON (rejected):** lean on Android's bundled `org.json` (like the §1.3 `meta/` config files), no external dep. Rejected because: (a) the entry is **hot-path-ish structured crypto material inside AEAD**, not human-managed config — §5's own rationale draws exactly this line ("roster/chat descriptor files stay JSON because they are human-managed config, not per-message hot path"); a directory entry is on the per-member crypto path, not human-edited config. (b) Binary keeps the public-key bytes and the signature as exact fixed-width fields, matching `publicIdentity()`'s 32-byte shapes and the §8.3 64-byte signature with no Base64 inflation or string-parse ambiguity. (c) Reusing the §8 TLV shape means one serialization idiom and one parser discipline across both inner formats — lower surface for the coder and the next reader. JSON would introduce a second, divergent inner-serialization style for no capability gain.

### Recommendation — Option 2A outer + binary versioned-TLV inner

Reuse the §5/§5.1 envelope frame for the outer wrap (verbatim `Envelope` + `MessageCrypto`), and a §8-style **binary versioned TLV** for the inner signed payload. This keeps the entire disk on **one framing idiom, one AEAD path, one reject discipline**, adds **no dependency**, and matches the plan's own characterization (a structured record that lands inside AEAD like a message). **Pin the inner signed-payload field order (for the coder authoring §10):**

```
offset  size   field                value / meaning
------  -----  -------------------  ---------------------------------------------------
0       1      dir-entry-version    0x01 — directory inner-format version (independent of
                                    §7 protocol, §5 envelope-version, §8 msg-format-version)
1       32     signing-pubkey       author's Ed25519 signing PUBLIC key (crypto_sign_PUBLICKEYBYTES)
33      32     box-pubkey           author's X25519 box PUBLIC key (crypto_box_PUBLICKEYBYTES)
65      8      version-counter      signed per-author monotonic supersede coordinate (Q3),
                                    big-endian uint64 — best-effort display time is NOT this
73      2      display-name-len     big-endian uint16, UTF-8 byte length of display-name (≤ cap)
75      L      display-name         UTF-8 bytes, RAW (no rendering — UI concern), length-prefixed
75+L    64     signature            Ed25519 detached signature over bytes [0 .. 75+L)
```

- **Signed range** = `dir-entry-version ‖ signing-pubkey ‖ box-pubkey ‖ version-counter ‖ display-name-len ‖ display-name` (everything before the 64-byte signature). Because `signing-pubkey` is **inside** the signed range, the claimed signer key is itself signed — it cannot be swapped without breaking the signature (same property as §8.3). **Verify** with `IdentityCrypto.verify(signature, signed-range, signing-pubkey)`; libsodium `-1` = hard reject, entry dropped (plan `wrong_signature_rejected`, `entry_signature_hard_rejects_on_failure`).
- **Bounds discipline (SC14 family):** cap `display-name-len` (e.g. a small fixed max, coder pins) and validate it against the remaining buffer before reading; a payload shorter than `75 + 0 + 64 = 139` bytes (min prefix + empty name + signature) is a reject, never a bounds exception. Same "validate every length prefix" rule as §8.1.
- **Self-published trust only:** the signature proves *the holder of `signing-pubkey`'s secret authored this entry*; it does **not** bind that key to a human — that is the deferred QR safety-number (do not introduce host attestation). The `DirectoryEntry { displayName, signingPublicKey(32), boxPublicKey(32) }` the read returns is exactly `publicIdentity()`'s shape, so a downstream DH/fingerprint consumer takes the bytes directly.

---

## Question 3 — Supersede / ordering for updated entries

A member may re-publish (display-name change, reinstall). Entries are append-only + content-addressed (§3) — a new file per version, never an in-place rewrite. The reader must resolve "latest valid entry **per member**", and the supersede coordinate must be **signed** (inside the signed payload), **monotonic**, and **NOT a security-trusted wall clock**.

### Per-member grouping key (confirm)

Group versions by the **Ed25519 `signing-pubkey`** carried in (and signed by) the entry. It is the natural stable per-member identity: it is inside the signed range (cannot be forged onto another's entry), it is the same key the signature verifies against, and it is exactly what a downstream consumer keys on. **Relationship to §1.2 `member-index-id`/`member-identifier`:** the §1.2 `member-index-id` is a hash of the *roster `member-identifier`* (an address/display string, "**no verified identity key in MVP**") used to name a chat's per-member **change-index folder**. The directory's grouping key is the **verified Ed25519 signing public key** — a *stronger, cryptographic* per-member key that the directory introduces for the first time. They are **different identifiers for different scopes** (chat change-index folder naming vs. community directory member identity) and must not be conflated: the directory does **not** name entry files by `member-index-id`, and §1.2 is untouched. (A future feature may relate a roster `member-identifier` to a published signing key via the directory — out of scope here.)

### Options for the coordinate

**Option 3A — signed per-author monotonic version counter (RECOMMENDED).** A `version-counter` (big-endian uint64) inside the signed payload, incremented by the author on each re-publish, starting at 1. The reader, per `signing-pubkey`, keeps the entry with the **highest** `version-counter` among valid entries; ties (same counter, e.g. a reinstall that reset the counter) broken deterministically by the §2-style **content-hash of the entry file** (the lexicographically larger name wins — an arbitrary-but-total, all-readers-agree tiebreak).

- Pros: **monotonic and entirely under the author's signed control** — no clock trust whatsoever; an attacker cannot make a stale entry win without the author's signing secret (the counter is signed); deterministic across all readers. Counter is author-local state (same idiom as the §4 per-sender `seq`).
- Cons: a **reinstall loses the counter** (fresh install starts at 1), so a post-reinstall entry could carry a *lower* counter than a pre-reinstall one for the same key — but a reinstall also means a **new keypair** (identity secret keys are Keystore-wrapped device-local, decision 10/SC5; they do not survive a reinstall), so the `signing-pubkey` changes and the entries are **grouped separately** — the old key's entries simply become orphaned/superseded-by-absence, not a wrong-winner. The honest limit: if a member somehow restores the *same* signing key but a *reset* counter, the content-hash tiebreak resolves it deterministically (possibly to the older display-name) — an acceptable, non-security-relevant display glitch (best-effort display, the plan's explicit allowance). Note this in §10 as the known counter-reset edge.

**Option 3B — signed timestamp-with-tiebreak.** A signed `published-at` unix-millis + a signed tiebreak, reader picks the latest.

- Pros: human-meaningful; survives reinstall ordering if the new clock is ahead.
- Cons: **the coordinate IS a wall clock** — exactly what the plan forbids as the security-trusted "which wins" coordinate. A lying/drifted clock (the author's own, or an attacker re-signing under a key they control) can make an arbitrarily-far-future timestamp win permanently, or an honest update lose to a backdated one. It is signed, so not *forgeable across members*, but it is still **clock-trusted for the supersede decision**, which the plan rules out. A wall-clock display *hint* is fine (best-effort), but it must not be the winner-selection coordinate.

### Recommendation — Option 3A

A **signed per-author monotonic `version-counter`** (uint64, inside the signed payload, content-hash tiebreak), grouped per **Ed25519 `signing-pubkey`**. It is monotonic, fully author-controlled, signed (tamper-evident), and **never trusts a wall clock for "which wins"** — satisfying the plan's hard requirement. A best-effort display time, if a UI later wants one, can ride as a separate **unsigned-or-signed display-only field** (not added now; not the winner coordinate) — exactly the §4 split between the trusted `order-token` and the display-only `send-timestamp`. **Spec for the coder (§10):** "Per `signing-pubkey`, the reader resolves to the valid entry with the maximum signed `version-counter`; ties broken by the lexicographically-greater entry file content-hash. The counter is best-effort-monotonic per author; a counter reset (rare, e.g. manual key restore) degrades only display selection, never security — it can never let an entry signed by a *different* key win for a member." Pin the reset edge as a known, accepted limitation.

---

## Question 4 — Local cache (in/out for this feature)

Whether verified entries get a local Room cache now (analogous to message history) or that is deferred to the UI feature.

### Options

**Option 4A — defer the Room cache to the UI feature (RECOMMENDED).** `readDirectory(communityKey)` returns the live `DirectoryReadResult` (the verified `DirectoryEntry` set + a rejected-count for diagnostics) computed **from disk each call**; no Room table is added by this feature.

- Pros: **smallest correct surface for a backend-only feature with no UI yet** — the plan explicitly has "no UI" and "discovery feeds downstream (not wired here)", so there is no consumer that needs offline browsing or a `Flow` today. Avoids a Room schema bump (a new entity + DAO + migration + `app/schemas/N.json`) for data nothing yet observes. Keeps the directory's persistence model honest: the **on-disk directory is the source of truth** (plan Contracts), and a cache would be pure optimization with no current beneficiary. The `sync` feature already owns the Room database; adding a directory entity now would couple two features' schemas prematurely. Mirrors how `identity` (also a substrate) defines no storage beyond Keystore.
- Cons: each read does a `PROPFIND Depth: 1` + N `GET`s (no offline directory). Acceptable: there is no UI to be offline *for* yet, and the read is one cheap listing + small entries.
- **Read path in the meantime:** `readDirectory` returns the freshly-verified set directly to the caller (in memory), recomputed per call. No durable local state, no `Flow`. When the UI feature lands, it owns the decision to add a Room-backed cache + an observable `Flow` (and the schema bump that entails), populated by `readDirectory`'s output — exactly analogous to how `sync` owns message history. State this explicitly in `docs/architecture.md` so the UI feature picks it up.

**Option 4B — add a directory Room cache now.** A `DirectoryEntryEntity` (PK = `signing-pubkey`, holding latest verified `version-counter` + display-name + box-pubkey) + DAO + `Flow`, populated on each read.

- Pros: offline browsing + a reactive `Flow` ready for the UI; resolves supersede once at write-into-cache time.
- Cons: a Room schema migration + checked-in schema JSON + DAO tests for data **no current consumer observes**; couples the directory's storage to the `sync`-owned database before the UI feature defines what it actually needs (paging? search? per-community scoping?); risks the cache and disk diverging (the disk is the source of truth, so a cache adds a staleness/eviction question with no UI to justify it).
- Risks: premature schema commitment — the UI feature may want a different shape (e.g. per-community keyed, last-verified timestamp for staleness), forcing a second migration.

### Recommendation — Option 4A (defer)

**Defer the Room cache to the UI feature.** This feature returns verified entries **in memory, recomputed per read, from the on-disk source of truth** — the correct surface for a backend substrate with no observer yet, consistent with `identity` (no storage beyond Keystore) and with the plan's "no UI / discovery feeds downstream (not wired here)". Record in `docs/architecture.md` that the directory read path is **disk-recompute, no local cache**, and that the cache + observable `Flow` (with its Room schema bump) is the **UI feature's** to add, populated from `readDirectory`'s output — the same ownership split as `sync`-owns-history. (The plan's optional "Local cache, if the architect places one" contract is therefore **not** instantiated this feature; note that for `pm-plan-checker`.)

---

## Consequences / risks summary (for the coder authoring `webdav-layout.md` §10 + implementing `directory/`)

- **New §10 in `webdav-layout.md`** (additive, does **not** touch §1–§9): community-root + reserved `directory/` collection (Q1); entry = §5 envelope frame wrapping an AEAD-sealed binary versioned-TLV inner payload with the pinned field order (Q2); per-author signed monotonic `version-counter` supersede coordinate grouped by Ed25519 `signing-pubkey`, content-hash tiebreak (Q3); read = `PROPFIND Depth: 1` + per-entry open/verify/resolve, reject-don't-guess + flat-trust degradation (one bad entry dropped, never wedges the read).
- **Entry file name:** content-addressed like §2/§3 — `b32lower(SHA-256(entry-file-bytes))[0:N]`, filename-safe (SC16), append-only (a new version is a new file, never an in-place rewrite, §3). Two senders/republishes never collide; supersede is resolved at read time, not by overwrite. (Coder pins N and whether a short readability prefix is prepended — keep it within the §0 alphabet, no `/`/`..`/spaces.)
- **Reuse, don't re-mint primitives:** `protocol/Envelope` (frame), `crypto/MessageCrypto.seal/open` (community key as a `ChatKey`, decision 9 family), `identity/IdentityCrypto.sign/verify` + `publicIdentity()` (decision 10), `protocol/HashTag`/`Base32` (filename-safe hashing). No new crypto, no hand-rolled primitive (SC9).
- **Community key handling (SC3/SC4 family):** symmetric AEAD key, same family as `random`/`known`, **independent of the disk credential** (SC3), **never written to disk / never logged** (SC4-family), supplied out-of-band/as config (onboarding feature distributes it — out of scope). The community key is **not** an access-control boundary on disk (one shared credential, Topology A) — confidentiality is the AEAD seal.
- **Clean seams left for deferred features (named, not designed):** `meta/community.json` owner marker (community feature) as a community-root sibling of `directory/`; host counter-signature (the entry's signed format does not preclude a later host signature, but none is added now); the UI feature's directory cache + `Flow`; key rotation (a rotated/stale-key member's entry simply fails AEAD-open for others, Scenario 6 — no active removal here).
- **Plan accuracy:** no plan revision needed — the plan's contracts (Publish, Read, DirectoryEntry, optional cache) match these recommendations; this note resolves the four open structural choices the plan delegated. Flag for `pm-plan-checker` only that the optional "Local cache, if the architect places one" contract is **deferred (Option 4A)**, not instantiated this feature.
- **Post-coding doc handoffs (per the plan's "Docs to update", owner `pm-architect`):** add a decision **"Directory substrate (community user directory)"** to `docs/architecture.md` (community key as a fourth-family symmetric AEAD key, self-published Ed25519-signed entries, the Q1 on-disk home, the Q3 supersede choice, the Q4 disk-recompute/no-cache decision), flip `app/.../directory/` to Implemented in the File-layout module map, and author the `docs/threat-model.md` Threat rows + `SCn`(s) the plan enumerates (display-name impersonation accepted-limitation; community-key-compromise community-barrier tier; directory metadata exposure — the same A5 metadata class as T17/T18). No `docs/user-journeys.md` change (no user-visible surface yet).
