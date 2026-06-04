# chat-directory — design notes

## Context

The `chat-directory` feature (`docs/features/chat-directory_plan.md`) adds the **community chat directory** to the shared WebDAV disk: each member publishes a signed, community-key-AEAD-sealed **chat descriptor** (chat-id + kind + access + title); any member lists, decrypts, verifies, and resolves the latest descriptor **per chat-id** to discover joinable chats without a per-chat invite. Backend only — no UI (the chat-list surface + the join action are later features). It consumes the done `identity` substrate (decision 10: `sign`/`verify`), the done `crypto` substrate (decision 9: AEAD `seal`/`open` with the community key — decision 12's fourth-family symmetric key), and reuses the `transport/` verbs unchanged. It authors a new community-scoped section **§11** in `docs/protocol/webdav-layout.md` and must not disturb §1–§10.

This is the **direct sibling of the done §10 user directory** (`docs/features/directory_plan.md`, `.ai-pm/arch/directory_arch.md`). The §10 directory already resolved the four cross-cutting structural questions a community-scoped collection raises (on-disk home, entry framing, supersede, cache), and the chat directory inherits all four answers. The structural choice that remains is **narrower and reuse-shaped**: the chat directory needs the **same machinery** (community-root home, §5-envelope-wrapped AEAD seal, binary versioned-TLV inner payload, signed monotonic supersede, content-addressed append-only entry names, Depth-1 reject-don't-guess read) but with a **different inner payload** (a chat descriptor `{chat-id, kind, access, title}`, not a member identity `{display-name, signing-pubkey, box-pubkey}`), a **different collection**, and a **different supersede grouping key** (chat-id, not signing-pubkey). The hard constraint that shapes the choice: **the §10 user directory — its behavior, its on-disk format, its public API — must not change**, and all §10 tests must stay byte-for-byte green.

Four sub-decisions, the same axes §10 pinned, re-resolved for the chat element:

1. **Where the chat-directory code lives + the reuse seam** (the genuinely new structural question — duplicate vs. extract-shared-substrate vs. middle).
2. **Supersede grouping key = chat-id** (confirm soundness + the security consequence under flat trust).
3. **Inner chat-descriptor field layout + enum encodings for §11** (pin the shape, advise the coder authoring §11 + the codec).
4. **No local cache** (confirm the §10 inheritance).

This is a real new axis (a second community-scoped collection with a distinct inner payload, distinct trust question, and a non-trivial reuse-vs-duplicate seam under a "do not touch §10" constraint) — a design note is warranted, not a "just add it" exit.

## Adjacent implementations

1. **`directory/DirectoryService`** at `app/src/main/kotlin/org/openwebdav/messenger/directory/DirectoryService.kt` — the §10 orchestration seam. Pure orchestration over `WebDavTransport` (verbs) + `DirectoryCrypto` (seal/open + sign/verify) + `DirectoryPaths` (collection + content-addressed name): `publishEntry` = sign → seal → content-address → idempotent `MKCOL` + `PUT`; `readDirectory` = `PROPFIND Depth: 1` → per-entry `GET`/content-hash-check/open/verify → `SupersedeResolver`. **No HTTP, no SQL, no crypto in this class** — it composes the lower layers. The chat directory's `publishChatEntry` / `readChatDirectory` are the **same orchestration shape** with a chat descriptor instead of an identity entry.

2. **`directory/DirectoryCrypto` + `DirectoryEntryCodec` + `DirectoryFormat`** at `…/directory/` — the inner-payload layer. `DirectoryCrypto` composes `signAndSerialize → MessageCrypto.sealEnvelope` (build) and `MessageCrypto.openEnvelope → codec.parse` (open), reusing `crypto/MessageCrypto` (§5 envelope + AEAD, header as AAD) and `identity/IdentityCrypto` (Ed25519 sign/verify) **verbatim**. `DirectoryEntryCodec` serializes/parses the §10.3 inner TLV with a bounds-checked `Cursor` (no `!!`, typed `Rejected` on every overrun) and verifies the Ed25519 signature LAST over the exact `[0..len-64)` signed range. `DirectoryFormat` is the single-source byte-constant object the writer and reader share. The chat-descriptor codec is **structurally identical** — a versioned prefix + length-prefixed fields + trailing 64-byte signature, same `Cursor`/reject discipline — over a different field set.

3. **`directory/SupersedeResolver`** at `…/directory/SupersedeResolver.kt` — the §10.5 latest-per-grouping-key reducer. It already groups by a **string key extracted from the entry** (`entry.copySigningPublicKey()` hex-joined), keeps the max signed `version-counter` (unsigned uint64 compare), and breaks ties by the lexicographically-greater content-addressed entry name. **The grouping key is the one thing that differs for the chat directory** (chat-id instead of signing-pubkey); everything else (counter compare, content-hash tiebreak, in-memory pure reduction) carries over unchanged — see Question 2.

4. **`directory/DirectoryPaths`** at `…/directory/DirectoryPaths.kt` — the content-addressed name minter: `entryName(bytes) = HashTag.tag(bytes, 32)` (the bare 32-char `[a-z2-7]` SHA-256, **not** a §2 message-id), `entryPath`, and `isWellFormedEntryName` (the SC16 grammar gate dropped **before** any `GET`). The chat directory's name minting is **byte-identical** (same `HashTag` primitive, same 32-char Base32-lower alphabet, same SC16 gate) — only the collection segment differs (`chat-directory/` vs `directory/`). `DirectoryResults` (`PublishOutcome` / `DirectoryReadResult`) and `DirectoryFactory` (the Android-backed wiring scoped to the community-root) are the remaining thin parts that have an exact chat-directory analogue.

## Behavioral risks in this area

The chat directory is **not** timer/event-driven (like §10, unlike `sync`): publish and read are called synchronously by a future consumer. **No poll loop, no cursor, no WorkManager** — the §9.3 cursor-advance hazard does **not** apply. The risks are the §10 risks, plus one new one (a second collection + a reuse seam):

- **Map of triggers → effects:** `publishChatEntry` → one AEAD-sealed content-addressed `PUT` into the chat-directory collection + an idempotent `MKCOL` of that collection. `readChatDirectory` → one `PROPFIND Depth: 1` + per-entry `GET`/open/verify, writing **nothing** to disk (and, no cache landing, nothing to Room). A read never writes — **no disk-write feedback loop**, exactly as §10.
- **A second community-scoped collection on the same disk (new vs. §10).** The chat directory shares the community-root, the one shared credential (flat trust), and the append-only/content-addressed discipline with **both** the §10 `directory/` **and** the per-chat `log/`/`changes/`. The new cross-feature hazard is **on-disk namespace collision**: the chat-directory collection must sit at a path that can never alias `directory/`, the community-level `meta/`, or any chat-root segment (resolved in Question 1's home choice). The plan asserts `chat_directory_read_does_not_touch_user_directory_or_chat_folders` — a chat-directory read/write touches **only** its own collection.
- **A refactor-to-share seam that must not regress §10 (the load-bearing risk).** If Question 1 extracts shared code from §10, the risk is a **behavior-preserving-refactor failure**: a generic-over-payload extraction that silently changes a §10 byte, a §10 reject path, or the §10 public API. This is the single highest risk of the feature and is what drives the Question-1 recommendation toward the **lowest-touch** option that still avoids real duplication — see below.
- **Flat trust (SC11), per entry:** any member can delete or tamper any chat-descriptor entry, **or publish a competing descriptor for a chat-id they do not own** (the new flat-trust consequence — Question 2). A tampered entry fails AEAD-open or `verify` as a typed rejection and is dropped without wedging the read of the remaining valid entries (`tampered_chat_entry_does_not_wedge_directory`). Same reject-don't-guess + flat-trust-degradation discipline as §3/§10.6, applied per entry — iterate, drop-on-reject, continue; no cursor to wedge.
- **A new read-time privacy gate (`dm`-drop):** unlike §10, the chat directory enforces a **content-level drop at read** — a `dm`-kind entry (validly sealed + signed) is dropped and never surfaced (`forged_dm_entry_dropped_others_read`). This is a privacy invariant enforced at read, not only at publish; it lives in the codec/read path, not the crypto path.

---

## Question 1 — Where the chat-directory code lives + the reuse seam

### The tension

The chat directory needs the **same machinery** as §10 (seal/open + sign/verify, content-addressed append-only names, signed monotonic supersede, Depth-1 reject-don't-guess read) over a **different inner payload** and a **different collection**, under a hard **"do not change §10"** constraint. The question is how much of §10 to share vs. duplicate vs. extract — and the dominant force is **migration risk to the done, tested §10 directory**, not abstract DRY.

A reading of the §10 code shows the reuse surface splits cleanly into two layers:

- **A byte-level / primitive layer that is already payload-agnostic or trivially so:** `DirectoryCrypto` (seal/open over *any* inner-payload bytes via `MessageCrypto`), `DirectoryPaths` (content-address *any* file bytes; the SC16 grammar gate is payload-independent), and `SupersedeResolver` (already reduces over a **string grouping key extracted from the entry** + a uint64 counter + a content-hash tiebreak — it never hard-codes "signing-pubkey", it takes whatever key the caller groups on). This layer is **inner-payload-shaped only at its edges**.
- **A per-directory layer that is genuinely payload-specific:** the inner codec/format (`DirectoryEntryCodec`/`DirectoryFormat` know the §10.3 field set) and the thin orchestration (`DirectoryService` knows the §10 contract names + the §10 collection). This layer **must be distinct** for a chat descriptor — its fields, its enums, its `dm`-drop gate, and its chat-id grouping differ.

### Options

**Option 1c — share the byte-level seal/open/sign/verify + path minting + supersede resolver; keep two thin per-directory services + codecs (RECOMMENDED).** Treat the §10 `DirectoryCrypto` (or a small extracted primitive it already is), `DirectoryPaths`-style content-addressing + SC16 gate, and `SupersedeResolver` (made grouping-key-agnostic at its **edge**, not its core — see below) as a **shared community-directory substrate**, and author a **new thin `chat-directory` package** (`ChatDirectoryService` + `ChatDescriptorCodec` + `ChatDescriptorFormat` + `ChatDirectoryPaths` + `ChatDirectoryResults` + a `ChatDirectoryFactory`) that builds on it with its own inner payload.

- Where: a new `app/.../chatdirectory/` package (the coder pins the leaf name); the shared primitives stay where the seam is lowest-touch.
- Relation to adjacent: **symmetric** with §10 — each directory is "a thin service + a codec over the shared seal/open + content-address + supersede primitives", differing only in the inner payload + grouping key + collection.
- The **only §10 touch** is making the share-points payload-agnostic **without changing §10 behavior, format, or public API**:
  - `SupersedeResolver` **already** takes a string grouping key derived from the entry. The cleanest seam is to have it group by a **caller-supplied key** (the chat-directory supplies the chat-id string; §10 supplies the hex signing-pubkey) rather than reaching into `DirectoryEntry`. If touching `SupersedeResolver` risks the §10 tests at all, the **zero-touch fallback** is to copy the ~50-line resolver into the chat package (it is small, pure, and total) — duplication of 50 trivial lines is cheaper than risking the done §10. Recommend the edge-generalization **only if** all §10 tests stay green; otherwise copy.
  - `DirectoryCrypto` is already payload-agnostic at the byte boundary (it seals/opens *bytes* and delegates inner parse to an injected codec). The chat directory can construct a `DirectoryCrypto`-shaped composer with its **own** codec — no §10 change needed.
  - `DirectoryPaths`' content-addressing + SC16 gate is fully payload-independent; the chat directory reuses the same `HashTag`-based minting with its own collection constant.
- Pros: **smallest blast radius on §10** — at most an edge-level generalization of one pure, total 50-line class, gated by "§10 tests stay green," with a trivial copy fallback. No §10 format/behavior/API change. Keeps each directory independently readable (its own codec + contract). Stays well within the AI size limits (each thin class is small; nothing approaches 300 lines). Matches the plan's own framing ("reuses the §10 patterns … with a distinct inner payload and a distinct collection").
- Cons: a small amount of structural parallelism between the two services (two thin orchestrations of the same shape). Acceptable — the *shape* is shared via the primitives; only the ~80-line orchestration skeleton is parallel, and it differs in contract names + the `dm`-drop gate + chat-id grouping.
- Risks: low. The one care point is the `SupersedeResolver` edge change — gated by the "§10 tests green" condition, with the copy fallback if it isn't.

**Option 1a — a fully self-contained sibling package that duplicates the thin orchestration AND its own codec, sharing nothing but the already-public `crypto`/`identity`/`transport`/`protocol` substrates.** No §10 file is touched at all; the chat package re-implements its own content-addressing, supersede, and seal-composition.

- Pros: **zero §10 risk** — §10 is not touched, period.
- Cons: duplicates the content-addressing + SC16 gate + supersede resolver + seal/open composition that §10 already solved and tested — three to four copies of pure, total, already-correct logic, for no capability gain. Two divergent copies of the supersede + content-address discipline drift over time; a future fix to one (e.g. a tiebreak edge) silently misses the other. More surface for the coder and the next reader than 1c.
- Risks: drift between two copies of security-relevant logic (supersede tiebreak, SC16 gate) — the kind of duplication the protocol's own "move-not-copy" discipline exists to avoid.

**Option 1b — extract a fully generic community-directory substrate (generic over the inner-payload type + grouping key) and refactor §10 onto it.** A `CommunityDirectory<P>` parameterized by a payload codec + a grouping-key extractor, with §10 reimplemented as one instantiation and the chat directory as another.

- Pros: maximal DRY; one substrate, two instantiations; a third community collection (a future "topics"/"rooms" directory) is free.
- Cons: **refactors the done, tested §10 onto a new generic** — the single highest-risk option against the "§10 must not change" constraint. A generic-over-payload abstraction is also a heavier idiom than the codebase currently uses (the existing classes are concrete and small); introducing a parameterized substrate now, for exactly two instantiations, is premature generalization that the YAGNI line and the AI complexity budget both argue against. Any §10 behavior/format/API regression from the refactor is a self-inflicted finding on a done feature.
- Risks: high — touches every §10 file to re-seat it on the generic; the blast radius is the entire done directory.

### Recommendation — Option 1c

**Share the byte-level seal/open + content-addressing + SC16 gate + supersede resolver as a community-directory substrate; author a new thin `chat-directory` package (service + codec + format + paths + results + factory) over it.** It is the only option that gets **real reuse of the security-relevant primitives** (one content-addressing rule, one SC16 gate, one supersede tiebreak — no drift) while keeping the **blast radius on the done §10 at its minimum**: at most an edge-level generalization of one pure, total ~50-line class (`SupersedeResolver` grouping by a caller-supplied key), **gated by "all §10 tests stay byte-for-byte green," with a copy fallback if any §10 test moves.** It avoids both 1a's drift-prone duplication of security logic and 1b's high-risk refactor of a done feature onto a premature generic. Each directory stays independently readable (its own codec + contract + `dm`-drop gate). **Migration risk, named explicitly:** the only §10 file that *might* be touched is `SupersedeResolver`; the rule for the coder is **green-§10-tests-or-copy** — if generalizing its grouping edge perturbs a single §10 test, copy the 50 lines into the chat package instead. No §10 format, behavior, public API, or on-disk byte may change; that is a DoD condition (`directory` suite green, plan Test plan).

**Seam left for the coder:** the new `chat-directory` package mirrors the §10 file set one-to-one — `ChatDirectoryService` (orchestration), `ChatDescriptorCodec` + `ChatDescriptorFormat` (Question 3's inner payload), `ChatDirectoryPaths` (content-address + SC16 gate + the new collection constant), `ChatDirectoryResults` (`PublishOutcome`/`ChatDirectoryReadResult`), `ChatDirectoryFactory` (community-root-scoped wiring) — building on the shared seal/open + supersede primitives. **On-disk home (inherited from §10 Option 1A, not re-litigated):** the chat-directory collection is a **reserved community-root sibling of `directory/` and the community-level `meta/`** (the coder pins the segment name in §11 — e.g. `chat-directory/`; it must be distinct from `directory/`, `meta/`, and any chat-root segment per the §10.1 collision rule). Ensured with idempotent `MKCOL`, listed with one `PROPFIND Depth: 1`. Community-root + community key are out-of-band config, never on disk (SC3/SC19) — the same wiring `DirectoryFactory` already does.

---

## Question 2 — Supersede grouping key = chat-id (confirm; the security consequence)

### Confirmation: chat-id grouping is sound

§10 groups by the verified Ed25519 `signing-pubkey` (one entry per **member** — the natural per-author identity, inside the signed range). The chat directory groups by **chat-id** (one logical descriptor per **chat** — any member may publish/supersede it under flat trust). This is **sound**, and the `SupersedeResolver` generalizes over the grouping key cleanly because the resolver's core already keys on a **string extracted from the entry** + a uint64 counter + a content-hash tiebreak — none of which is intrinsically "signing-pubkey":

- **§10 supplies** the hex-encoded verified signing-pubkey as the grouping key.
- **The chat directory supplies** the (verified-signature-attested, but flat-trust-authored) **chat-id** as the grouping key.
- Everything downstream is **unchanged**: per group, keep the **maximum** signed `version-counter` (unsigned uint64 compare, so a counter ≥ 2^63 still orders correctly), ties broken by the **lexicographically-greater content-addressed entry name** (§10.4/§11 name) — an arbitrary-but-total tiebreak all readers agree on. The **content-hash tiebreak + signed monotonic version-counter pattern carries over unchanged.**

So the only difference is **which string the caller groups on**. That is exactly the edge generalization Question 1 isolates (group by a caller-supplied key), with the green-tests-or-copy rule.

### The security consequence (must be recorded in §11 + the threat model)

The grouping-key change has a **real security consequence** that §10's signing-pubkey grouping does not have, and it must be stated plainly:

- **In §10**, the grouping key **is** the signature's own verification key — `signing-pubkey` is inside the signed range, so an entry signed by a *different* key can **never** win for a member. The signature both authenticates the version **and** binds the grouping (the §10.5 security invariant).
- **In the chat directory**, the grouping key is the **chat-id**, which is **not** the signature's verification key. The signature still proves **tamper-evidence on the bytes** and **authorship of *this* version** (the holder of the signing key authored these bytes), but it does **NOT** prove **chat-ownership authority**: under the one shared credential (flat trust, SC11), **any member can sign and publish a competing descriptor for any chat-id** — a different, validly-signed descriptor for the same chat-id, with a higher `version-counter`, will win the supersede. The signature does not, and cannot here, gate *who may publish for a chat-id*.

This is the **flat-trust limit applied to chat descriptors**, parallel to §10's name↔human deferral (T20) and the SC11 model:

- A member can publish a competing/spoofed descriptor (a wrong title, a flipped `access`) for a chat-id they do not own; the latest signed version wins (the descriptor is **advisory discovery metadata, not authoritative chat ownership**).
- The remedy is an **authoritative chat-ownership marker** (a creator-signed / host-attested "this descriptor is authoritative for this chat-id"), which is **deferred** (plan Out of scope) — exactly parallel to §10's deferred host-attestation. The signed-descriptor format does **not preclude** a later authority counter-signature; the seam is left clean.
- A reader's safety property is therefore **bounded**: it gets a **community-keyed, signature-tamper-evident, latest-version** descriptor per chat-id — not a proof that the descriptor's author owns the chat. The honest limit is recorded.

**For `pm-architect`'s later threat-model handoff** (not this run): this is the plan's threat (a) "chat-descriptor spoofing / unauthorized supersede" — an **accepted limitation** Threat row, with its `SCn` noting the signature is tamper-evidence + authorship of the version, not ownership authority. The new enforceable rule "DMs are never published; a `dm`-kind entry is a hard reject on read" is a separate `SCn` (Question 3 + the plan's Docs-to-update).

---

## Question 3 — Inner chat-descriptor field layout + enum encodings for §11

Advice for the coder authoring §11 + the `ChatDescriptorCodec`/`ChatDescriptorFormat`. **Pin the shape, not every byte** — the exact section number, the chat-id grammar (`[?]`), and the final cap values are the coder's to pin against the existing spec, mirroring §10.3 / §8.

### Recommended inner signed-payload shape (mirror §10.3)

The inner payload reuses the **§10.3 idiom verbatim**: a versioned prefix + length-prefixed variable fields + a trailing 64-byte Ed25519 signature over the preceding bytes, AEAD-sealed under the community key inside the §5 envelope. Recommended field order (big-endian, §0; the coder pins exact offsets + the `[?]` chat-id width):

```
size   field                  value / meaning
-----  ---------------------  ---------------------------------------------------------
1      chat-entry-version     0x01 — chat-directory inner-format version (independent of
                              §7 protocol, §5 envelope-version, §8 msg-format-version,
                              §10.3 dir-entry-version)
32     signing-pubkey         author's Ed25519 signing PUBLIC key (crypto_sign_PUBLICKEYBYTES) —
                              who authored THIS version (NOT chat-ownership authority, Q2)
8      version-counter        signed per-chat-id monotonic supersede coordinate (Q2),
                              big-endian uint64 — best-effort display time is NOT this
1      kind                   0x01 = group  [0x00 = dm → REJECT, never valid here]
1      access                 0x00 = public, 0x01 = private  [other → REJECT]
2      chat-id-len            big-endian uint16, byte length of the opaque chat-id (≤ cap)
L1     chat-id                opaque, length-prefixed bytes — grammar still [?] (§8), carried
                              verbatim, NOT a path segment (lives inside the AEAD seal)
2      title-len              big-endian uint16, UTF-8 byte length of title (≤ cap)
L2     title                  UTF-8 bytes, RAW (no rendering — UI concern), length-prefixed
64     signature              Ed25519 detached signature over everything before it
```

**Shape decisions (the load-bearing advice):**

- **`signing-pubkey` stays inside the signed range** (as in §10.3): the claimed author key is itself signed — it cannot be swapped without breaking the signature. It is **NOT** the supersede grouping key (chat-id is, Q2); it is published to attribute *who authored this version* and to verify the signature. The reader still verifies the signature against this carried key (hard reject on libsodium `-1`).
- **`kind` and `access` are single fixed bytes**, not length-prefixed — they are closed small enums, so a byte each (with reserved values = reject) is the §8.5 `reaction-index` idiom (a closed-range byte, out-of-range = reject). `kind`: `group` only is valid; **`dm` is a hard reject on read** (privacy enforced at read, Q2 + Scenario 2/6) **and** rejected at publish (never written, Scenario 1). `access`: `public`/`private`; any other value = reject (reject-don't-guess). Putting `kind`/`access` **before** the variable-length chat-id/title lets the reader reject a `dm` or an invalid `access` **before** reading the variable fields (cheap fail-fast — but note the signature still gates surfacing, so the drop is final either way).
- **`chat-id` is opaque + length-prefixed**, carried verbatim (its grammar is `[?]` in §8, pinned by a later feature — the same deferral as `message-model`). It is **NOT a path segment** (it lives inside the AEAD-sealed payload, never in a file name — the entry file name is content-addressed, §11/§10.4), so it is **not** restricted to the §0 filename-safe alphabet; the SC16 path-safety rule applies to the **entry file name**, not the chat-id. Cap its length (a small fixed max, coder pins) and **bounds-check the length prefix against the remaining buffer before reading** (no `!!`, no bounds exception — the §10.3 `Cursor` discipline).
- **`title` is raw length-prefixed UTF-8, capped** — mirror §10.3's display-name cap (**256 bytes**, the plan's explicit anchor). Raw bytes, no rendering/normalization (UI concern). Over-cap or overrun length = reject.
- **Signed range** = everything from `chat-entry-version` through `title` (excluding the trailing 64-byte signature) — the §10.3/§8.3 rule. **Minimum valid payload** = fixed prefix + empty chat-id + empty title + signature; a payload shorter than that is a reject, not a bounds error (the §10.3 `MIN_PAYLOAD_BYTES` pattern).

### Where the §11 reject-don't-guess validations live

All in the **codec parse path** (the `ChatDescriptorCodec.parse`, mirroring `DirectoryEntryCodec.parse`), as typed rejections — never thrown, dropped at read, the read continues:

- **unknown `chat-entry-version`** → reject (§7/§10.3 reject-don't-guess).
- **`kind == dm` (0x00) or any non-`group` value** → reject (the new privacy `SCn` + Scenario 2/6 `dm`-drop). This is the **content-level privacy gate** unique to the chat directory.
- **`access` outside `{public, private}`** → reject (`invalid_kind_access_combo_rejected`).
- **any length prefix that overruns the remaining buffer**, a **wrong-width `signing-pubkey`**, an **over-cap chat-id or title**, **trailing bytes between the last field and the signature**, or a **payload below the structural minimum** → reject as `MALFORMED` (the §10.3 `Cursor`-bounds discipline, no `!!`).
- **signature verify failure** (libsodium `-1`) → hard reject (`wrong_signature_rejected`, `chat_entry_signature_hard_rejects_on_failure`).
- **SC16 entry-name grammar** is checked in the **paths** layer (the `ChatDirectoryPaths.isWellFormedEntryName` analogue) **before** any `GET` — a foreign/malformed name is dropped, never dereferenced (`chat_directory_path_rejects_traversal`). The chat-id (opaque, inside the seal) is **not** subject to this — only the file name is.

The verified record the read returns is `ChatDirectoryEntry { chatId, kind (group), access (public|private), title, publishedBySigningKey(32) }` — `publishedBySigningKey` is the 32-byte Ed25519 key that signed this version (who authored it, **not** chat-ownership authority — Q2).

---

## Question 4 — No local cache (confirm)

**Confirmed — the chat directory inherits §10's no-cache decision (Option 4A).** `readChatDirectory(communityKey)` returns the live `ChatDirectoryReadResult` (the verified `ChatDirectoryEntry` set, latest per chat-id, + a rejected count + a `listingFailed` flag) **recomputed from the on-disk source of truth per read**; **no Room table is added by this feature**. The reasoning is identical to §10's and unchanged:

- The plan is explicit "no UI / discovery feeds downstream (not wired here)" — there is **no consumer that needs offline browsing or an observable `Flow` today**, so a cache would be pure optimization with no current beneficiary.
- Avoids a Room schema bump (a new entity + DAO + migration + checked-in schema JSON) for data nothing yet observes, and avoids coupling the chat directory's storage to the `sync`-owned database before the UI feature defines what it actually needs.
- Keeps the persistence model honest: the **on-disk chat directory is the source of truth** (plan Contracts); the local-history-at-rest encryption of any future cache is the same accepted limitation as SC17/T16.
- **The Room-backed cache + observable `Flow` (with its schema bump) is the UI feature's to add**, populated from `readChatDirectory`'s output — the same ownership split as §10 (arch Option 4A) and as `sync`-owns-history. Record this in `docs/architecture.md` (post-coding handoff) so the UI feature picks it up. The plan instantiates **no** "Local cache" contract this feature.

---

## Consequences / risks summary (for the coder authoring `webdav-layout.md` §11 + the `chat-directory` package)

- **New §11 in `webdav-layout.md`** (additive, does **not** touch §1–§10): the chat-directory collection as a reserved community-root sibling of `directory/` + `meta/` (Q1, the §10.1 collision rule); entry = §5 envelope frame wrapping an AEAD-sealed binary versioned-TLV chat-descriptor with the Q3 field order; per-chat-id signed monotonic `version-counter` supersede grouped by **chat-id** (Q2), content-hash tiebreak; the `group`-only / `dm`-rejected / `access ∈ {public, private}` validity rule; read = `PROPFIND Depth: 1` + per-entry open/verify/`dm`-drop/resolve, reject-don't-guess + flat-trust degradation (one bad entry dropped, never wedges the read). Mirror §10.1–§10.6's section shape.
- **Code home (Q1, Option 1c):** a new thin `app/.../chatdirectory/` package (service + codec + format + paths + results + factory) over the **shared** seal/open + content-address + SC16-gate + supersede primitives. The **only** §10 touch permitted is an edge-level grouping-key generalization of `SupersedeResolver` — **gated by "all `directory` (§10) tests stay green," with a copy-the-50-lines fallback** if it isn't. **No §10 format, behavior, public API, or on-disk byte changes** (DoD condition).
- **Supersede grouping = chat-id, not signing-pubkey (Q2):** the signature is **tamper-evidence + authorship of this version, NOT chat-ownership authority** — under flat trust any member can publish a competing descriptor for a chat-id; latest signed version wins; the authoritative chat-ownership marker is **deferred** (clean seam, parallel to §10's host-attestation). Content-hash tiebreak + signed monotonic counter carry over unchanged.
- **Inner descriptor shape (Q3):** `chat-entry-version ‖ signing-pubkey(32) ‖ version-counter(uint64) ‖ kind(byte) ‖ access(byte) ‖ chat-id-len(uint16) ‖ chat-id(opaque) ‖ title-len(uint16) ‖ title(UTF-8, cap 256) ‖ signature(64)`; `signing-pubkey` inside the signed range; `dm`-reject + invalid-`access`-reject + length-bounds + signature-verify all in the codec parse path (typed rejection, no `!!`); chat-id opaque-not-path-segment (SC16 gates the **file name**, not the chat-id). Cap/offset/section-number/`chat-id` grammar are the coder's to pin against §11. The `dm`-drop-on-read is the **content-level privacy gate** unique to this directory.
- **No local cache (Q4):** disk-recompute read, in memory, per call. The Room cache + `Flow` (+ schema bump) is the UI feature's, populated from `readChatDirectory`'s output. No "Local cache" contract instantiated this feature (note for `pm-plan-checker`).
- **Reuse, don't re-mint primitives:** `protocol/Envelope` + `crypto/MessageCrypto.seal/open` (community key as a fourth-family `ChatKey`, decision 9/12), `identity/IdentityCrypto.sign/verify` + the author's `signing` public key (decision 10), `protocol/HashTag`/`Base32` (filename-safe content-addressing), and the shared §10 seal-composition + supersede + SC16-gate primitives (Q1). No new crypto, no hand-rolled primitive (SC9), no new dependency, no new transport verb, no new validator (`CLAUDE.md` Pipeline unchanged).
- **Community key + community-root handling (SC3/SC19 family):** symmetric AEAD key, same family as `known`/`random`/§10's community key, **independent of the disk credential** (SC3), **never written to disk / never logged** (SC4/SC19 family), supplied out-of-band / as config (onboarding distributes it — out of scope). Not an access-control boundary on disk (one shared credential, Topology A) — confidentiality is the AEAD seal; the key bounds *who* reads to onboarded community members (the community-barrier tier, SC19). A private group's **existence + title** are discoverable within that barrier; the private group's **content key is NEVER in the directory** (plan Scenario 6 — the key stays out-of-band).
- **Clean seams left for deferred features (named, not designed):** the authoritative **chat-ownership marker / host counter-signature** (the signed-descriptor format does not preclude it — Q2); the UI feature's chat-directory cache + `Flow` (Q4); the **chat-id grammar** pinning (`[?]`, a later feature); **key rotation / per-member revocation** (a rotated/stale-key member's entry simply fails AEAD-open for others — Scenario 6 — no active removal here); the **private-chat key distribution** (onboarding/invite feature — the directory surfaces existence + metadata only).
- **Plan accuracy:** no plan revision needed — the plan's contracts (Publish, Read, ChatDirectoryEntry, Kind/Access) match these recommendations; this note resolves the four structural choices the plan delegated. Flag for `pm-plan-checker` only that no "Local cache" contract is instantiated (Q4, deferred), and that the §10 reuse seam (Q1) carries the **green-§10-tests-or-copy** rule as a DoD condition.
- **Post-coding doc handoffs (per the plan's "Docs to update", owner `pm-architect` — NOT this run):** add a decision **"Chat directory substrate (community chat directory)"** to `docs/architecture.md` (companion to decision 12 — the chat-id grouping, the group-only/`dm`-excluded scope, the self-published-signed-per-chat-id supersede with the deferred ownership marker, the no-cache decision, and the `app/.../chatdirectory/` module-map row); and author the `docs/threat-model.md` Threat rows + `SCn`(s) the plan enumerates — (a) chat-descriptor spoofing / unauthorized supersede (accepted limitation, flat-trust, parallel to T20); (b) private-group existence/title exposure to community members (community-barrier tier, content key never in directory, analogous to T21); (c) chat-directory metadata exposure to the disk operator (A5 metadata class, parallel to T22); plus the **new enforceable rule** "DMs are never published; a `dm`-kind entry is a hard reject on read" as its own `SCn` (`pm-architect` decides whether to generalize SC18/SC19 to both directories or add new `SCn`, wiring threat → constraint by ID). No `docs/user-journeys.md` change (no user-visible surface yet — the UI feature owns the discovery journey).
```
