# WebDAV on-disk protocol layout

> **Load-bearing interoperability contract.** This is the byte/path-level spec that every app instance reads and writes against. The **transport** feature (`docs/features/webdav-transport_plan.md`) implements the WebDAV access rules, file naming, and append-only rule; the **sync** feature (`docs/features/sync_plan.md`) reworks the folder/poll layout to the shared-log + per-user change-index model and implements send + the background poll cycle. The **crypto** and **compression** features fill the marked opaque slots. Two app instances interoperate if and only if they agree on this document.
>
> **Scope:** MVP, generic WebDAV (RFC 4918) + Yandex.Disk only. **No** Nextcloud-specific request shaping (separate plan), **no** scheduling/foreground-service, **no** multi-disk. Authoritative for the `[?]` invariants in `docs/architecture.md` → *Behavioral contract* (message-id grammar, shared-log/change-index/file-naming, ordering token, envelope codec-id). Where this spec and the architecture's Behavioral contract overlap, **this file is the source of truth** and the architecture points here.
>
> **Trust model recap (decision 2, Topology A):** all members of a chat share **one** WebDAV credential (one app-password) scoped to one folder. To the provider they are the same disk identity. Every member can **read AND delete every file** in the shared space. Nothing below is an access-control boundary — the shared-log / change-index split is a *read-efficiency* layout only.

**Protocol version:** `2` (see [§7 Versioning](#7-versioning)). **Layout generation 2** replaces the v1 per-recipient inbox fan-out with a **shared per-chat log + per-user change index + reserved retention window** (sync feature, 2026-06-04). The byte-level pieces below — §2 message-id grammar, §3 append-only + content-hash-on-read, §4 order-token, §5/§5.1 envelope framing + AEAD, §8 inner message format — are **unchanged** from v1; only §1 (folder layout), the inbox-specific parts of §3/§6, and the new §9 (change index + cursor) changed.

**Status:** transport slots fixed; crypto slot fixed (filled by the crypto feature); message-plaintext format fixed (§8, filled by the message-model feature); folder/poll layout reworked to generation 2 (filled by the sync feature); retention-window pruning reserved (§1.4, **deferred** — not implemented this feature); compression slot reserved (marked `filled by <feature>`).

---

## 0. Conventions & character sets

- **Path separator:** `/`. All paths below are relative to the **chat-root** (§1) unless they start with the provider base URL.
- **Encoding:** every path segment and file name uses only the **filename-safe alphabet**:
  `[A-Za-z0-9._-]` plus the explicitly-allowed `~` separator inside message-ids (§2).
  This set is chosen to be a valid, non-escaped filename on Yandex.Disk and generic WebDAV, and to survive percent-encoding round-trips unchanged (none of these characters require URL-escaping in a WebDAV path).
- **No uppercase-only distinctions:** treat names as case-sensitive when present, but never rely on two names that differ only in case (some providers fold case). All names this spec mints are lowercase except the Base32 hash alphabet (§2), which is lowercase too.
- **No spaces, no `+`, no `%`, no `:`, no `/` inside a segment.** These either need escaping, are reserved, or are rejected by some WebDAV servers.
- **Endianness:** all multi-byte integers in the binary envelope (§5) are **big-endian (network byte order)**.

---

## 1. Folder layout

One chat == one shared chat-root folder, reachable by one credential (decision 2). **Layout generation 2** (sync feature): the chat-root holds a **shared per-chat message log** (one copy of each message) and a **per-member change-index** collection (a small cursor structure per member), instead of the v1 per-recipient inbox tree (one full copy of each message per recipient).

```
<webdav-base-url>/<chat-root>/           # the one shared folder for this chat
├── meta/                                # chat metadata / roster (see §1.3)
│   ├── chat.json                        # chat descriptor: chat-id, chat-type, protocol-version  [populated by a later feature]
│   └── roster.json                      # member list (recipient identifiers)                     [populated by a later feature]
├── log/                                 # the ONE shared per-chat message log (see §1.2)
│   ├── <message-id>                     # one file per message, content-addressed (§2, §3) — ONE copy, not per-recipient
│   ├── <message-id>
│   └── <message-id>
└── changes/                             # parent of all per-member change indices (see §9 / §1.2)
    ├── <member-index-id>/               # one folder per member (see §1.2) — that member's change cursor(s)
    │   └── <change-entry>               # small per-(member,chat-change) cursor marker (§9.2)
    └── <member-index-id>/
        └── <change-entry>
```

### 1.1 Chat-root

- The **chat-root** is the folder the credential is scoped to. Its path is supplied in the connection config (base URL + app-password + chat-root path) and is **never** written to the disk (it is local config, decision 2 / Security constraints).
- Directly under the chat-root live exactly three collections: **`meta/`**, **`log/`**, and **`changes/`**. The transport ensures all three exist with `MKCOL` (§6) before first use. No message files live directly under the chat-root.

### 1.2 Shared log + per-member change-index folders

- **`log/` — the one shared per-chat message log.** Every message in the chat is written **once** into `log/<message-id>` — **not** copied per recipient. The file name is the §2 message-id (content-addressed), the file content is the §5 envelope, and the append-only + on-read content-hash rules (§3) apply unchanged. This collection is the disk's **bounded catch-up window** (§1.4): an offline member returning within the window catches up by reading the entries newer than its stored cursor (§9). Because the order-token is the lexicographically-sortable prefix of the §2 name (§4), the log is naturally cursor-ordered by file name — no separate index file is needed to order it.
- **`changes/<member-index-id>/` — one change-index folder per member.** A sender, on send, writes a small **change entry** (§9.2) into the change-index of **every other member** so that member's next poll learns "this chat changed for you, from this cursor" in one cheap request — without the sender copying the full message N times. The change-index folder holds only small cursor markers, never message bodies.
- **`<member-index-id>` derivation (deterministic, sender-computable):**

  ```
  member-index-id = b32lower( SHA-256( utf8(member-identifier) || 0x1F || utf8(chat-id) ) )[0:26]
  ```

  - `member-identifier` is the member's address/display identifier from the roster (the `sender`/address field of the envelope, §5; no verified identity key in MVP).
  - `0x1F` (ASCII Unit Separator) is a domain separator so `member="ab" + chat="c"` and `member="a" + chat="bc"` cannot collide.
  - `b32lower` is RFC 4648 Base32 in lowercase, no padding, alphabet `abcdefghijklmnopqrstuvwxyz234567`.
  - Truncated to **26 characters** (130 bits of the digest) — far beyond collision risk for a chat's membership, and a short, fixed-length, filename-safe segment.
  - **This derivation is identical to the v1 `recipient-inbox-id` derivation** (same inputs, same hash, same truncation). The byte function is unchanged across the generation-2 rework — only the folder it names changed (a member's **change index** rather than a per-recipient full-message **inbox**). An implementation keeps the same hashing code; it points it at `changes/` instead of `inbox/`.
- **Why a hash, not the raw identifier:** the raw member-identifier may contain characters outside the §0 alphabet (e.g. an email-like address or unicode display name). Hashing yields a fixed-length, always-filename-safe segment, and avoids leaking the raw identifier as a literal folder name on the disk. It is **deterministic** so any sender independently computes the same change-index path for a given member — that is how a sender **locates each member's change index to notify** (compute the id for every roster member, write a change entry into each).
- **This is not isolation.** Any member can list/read/delete the shared `log/` and any `changes/<member-index-id>/` (one credential). The split exists so a reader does **one** cheap `PROPFIND Depth: 1` on its **own** change index (decision 3), learns which chats changed and from which cursor, then fetches only the new `log/` entries — not so any member is isolated from another. See §3 (flat-trust degradation) for what happens when a change index is tampered or deleted.

### 1.3 Chat metadata / roster (`meta/`)

Named **now** so the layout is stable, but **populated by a later feature** (roster/chat-management). Neither the transport nor the sync feature writes these; they only ensure the folder exists.

- **`meta/chat.json`** — chat descriptor: `{ "protocol": 2, "chat-id": "<id>", "kind": "dm" | "group", "access": "public" | "private" }`. The chat taxonomy (the `kind` and `access` axes, and the optional password on private chats) is the **two-axis model** fixed in `docs/architecture.md` → Behavioral contract → *Chat taxonomy* (changing it is an architectural decision). This spec only reserves the path and JSON shape; the exact `chat.json` field names/shape are pinned by the later roster/chat-management feature against that taxonomy — keep this a **forward pointer**, do not restate the taxonomy here.
- **`meta/roster.json`** — member list: the member-identifiers a sender writes change entries to (§9.1). **In the sync feature the member set is supplied out-of-band / from config** (decision 2) — the sender takes the roster as input and writes a change entry per member; sync does **not** manage membership. A later roster/directory feature owns writing and signing `meta/roster.json`. The change-index write (§9.1) takes the member set as a parameter precisely so it does not depend on this file existing yet.

> `meta/` carries **no secret** material (no passphrase, no key — Security constraints). Whether the roster itself is encrypted is the roster feature's call; this spec only reserves the path and the JSON shape.

### 1.4 Retention window (RESERVED — pruning DEFERRED)

The shared `log/` (§1.2) is conceptually a **bounded retention window** of a chat's recent messages — the disk holds a window (by time and/or count), and full history lives unbounded locally on each device in Room (`docs/architecture.md` decision 3; backlog "Disk space / retention", 2026-06-03). The window is what lets an offline member catch up on return and (later) a new member get starter history.

- **This feature RESERVES the window concept; it does NOT implement pruning.** No reader or writer in the sync feature deletes old `log/` entries. The shared log grows within the window concept but is **not auto-trimmed** by this feature. Reading and cursor advance (§9) are window-agnostic — they walk whatever is present in `log/`.
- **A later pruning feature trims `log/` by time/size with NO format change.** Because pruning only `DELETE`s old `log/<message-id>` files (and never touches the §2 naming, the §5 framing, or the §9 cursor semantics), it slots in without a protocol-version bump. The boundary is explicit here so the pruning feature has a reserved home and the sync coder does not implement trimming now.
- **Honest limit (already in the threat model / non-goals):** a member offline longer than the window loses the oldest messages from the disk (the disk window is finite and there is no server); the loss is bounded by the window length, which a later feature makes generous + configurable. Within the window there is **no loss and no double-surfacing** (§9 cursor + §2 message-id dedup guarantee this).

---

## 2. message-id grammar

The message-id is **content-addressed** and doubles as the file name. Two concurrent writers never collide, and a duplicate send is idempotent: same id → same path → idempotent `PUT`.

**Grammar (fixed — unchanged in generation 2):**

```
message-id   = order-token "~" content-hash
order-token  = <see §4>                      ; 29 chars, lexicographically sortable, [0-9a-z-]
content-hash = b32lower( SHA-256(file-bytes) )[0:32]   ; 32 chars, [a-z2-7]
```

- **Separator:** a single `~` (tilde). `~` is unreserved in RFC 3986 paths, valid on Yandex.Disk and generic WebDAV, and does not appear in either the order-token alphabet (`[0-9a-z-]`) or the Base32 alphabet (`[a-z2-7]`), so the split point is unambiguous.
- **`content-hash`** is the Base32-lowercase (no padding) SHA-256 of the **exact file bytes** = the full message-envelope bytes as written to disk (§5: magic + version + header + ciphertext blob). It is computed over the bytes the writer `PUT`s, so any two writers producing identical envelope bytes produce the identical name → the same path → idempotent.
  - **Note for the crypto feature:** because the AEAD nonce is part of the envelope and is normally random, two *independent* encryptions of the "same" plaintext yield *different* bytes and therefore *different* ids — which is correct (they are different messages). True idempotency (a retried `PUT` of the *same* message) requires the writer to reuse the *same* envelope bytes on retry. The transport guarantees this: it computes the name from the finished bytes and retries the *same* bytes, never re-encrypts on retry.
- **Total length:** `29 + 1 + 32 = 62` characters — under every common WebDAV/filesystem name limit (255 bytes) with wide margin, even after the `log/` path prefix.
- **Allowed character set:** `[0-9a-z~-]` only. Fully filename-safe per §0; no escaping needed in a WebDAV path.

**Why content-hash in the name (not just a random uuid):** it makes the name a function of content, giving free idempotency and letting a reader cheaply detect that two listings of the *same* message (same `content-hash` suffix) are identical for dedup — without opening (decrypting) them. In generation 2 there is only one copy per message (in `log/`), so the cross-inbox dedup the v1 fan-out needed is gone; the content-hash still serves dedup-across-cycles (a re-listed entry is the same name → the same local row, §9).

---

## 3. Shared-log file-naming + append-only rule

- **One file per message — ONE copy in the shared `log/`.** The file name **is** the message-id (§2). There is no separate metadata file per message, and **no per-recipient copy** (the generation-2 change: a message is written once to `log/`, not fanned out into every recipient's folder).
- **Append-only — writers only `PUT` new files; never modify or overwrite an existing one.** A writer that finds the target name already present treats the `PUT` as already-done (idempotent success), not as a conflict. A writer **never** issues a `PUT` that replaces existing content at a message path, and **never** `PROPPATCH`/`MOVE`/`COPY`s a message file. The same rule applies to change-index entries (§9.2): a change entry is a new content/cursor-addressed file, never an in-place rewrite.
- **Why append-only (compensation for the flat trust model):** under one shared credential (decision 2) there is no per-author protection. Content-addressing + AEAD gives **tamper-detection of content** — if a byte changes, the content-hash in the name no longer matches `SHA-256(file-bytes)`, so a reader detects the mismatch and rejects the file. Append-only means no member silently rewrites another's message *in place*. **This does not give deletion resistance** — any member can still `DELETE` any file; deletion resistance (signatures, witnessed logs) is explicitly future work (architecture → Security constraints SC11).
- **Reader integrity check (required):** on `GET`, a reader recomputes `b32lower(SHA-256(file-bytes))[0:32]` and compares it to the `content-hash` suffix of the file name. **Mismatch → reject the file** (treat as tampered/corrupt; skip, do not surface as a message). This is the on-read enforcement of content-addressing and is independent of (and additional to) AEAD authentication, which the crypto feature adds inside the blob.
- **Incomplete-write tolerance (transport interaction scenario):** a `GET` that returns a truncated/failed body, or whose recomputed hash does not match the name, is treated as **"not ready"** — skipped this cycle, retried on the next `PROPFIND`/`GET`. Never surfaced as corruption (matches plan Interaction scenario *reader_skips_incomplete_file* / *incomplete_get_skipped_and_retried*).
- **Transient “not-ready” vs. permanent bad file — the read-rule's interaction with the §9.3 cursor (no loss vs. no wedge).** Content-addressing alone cannot distinguish *still-being-uploaded* from *forged/tampered* at read time (the Yandex “hash computed after upload” window and a planted-file mismatch are byte-indistinguishable on `GET`), so the bullet above's single “not ready → skip + retry” bucket is resolved **positionally** by the §9.3 reader — do not re-collapse it to one bucket. A truncated body or hash mismatch that opens a **new forward order-token coordinate gap** (newer than everything resolved this cycle) is treated as **transient**: the reader **blocks its cursor before that coordinate** and retries next cycle, so a later complete entry can never cause it to be skipped past (§9.3 no-loss). A hash mismatch (or unsupported codec / AEAD / signature failure — a permanently bad or forged file any member can plant under the flat-trust model, decision 2 / next bullet) at a coordinate that is **already witnessed or shared with an already-resolved entry** is treated as **permanent**: the reader **resolves (advances past) that coordinate**, so a single planted file cannot wedge the cursor and freeze the chat for everyone (§9.3 no-wedge). The positional rule (which coordinate gap a mismatch opens) lives in §9.3; this bullet only records that §3's reject/not-ready rule defers to it — it is not two independent skip rules.
- **Flat-trust degradation of the change index (generation 2):** because the change index is in the same shared space under the same credential, any member can **tamper with or delete** another member's `changes/<member-index-id>/` entries — the same flat-trust limit as the v1 inbox (SC11). The design degrades **safely**: a member whose change index is damaged or empty can **always rebuild its catch-up state by scanning the shared `log/` directly** — one `PROPFIND Depth: 1` on `log/` lists every message name (the order-token prefix gives ordering and the cursor position), so the change index is an **optimization, not a source of truth**. The shared log is the ground truth; the change index only saves the cost of listing the whole log every cycle. A member who distrusts its index, or whose index was deleted, falls back to a full `log/` listing and loses nothing within the window.

---

## 4. Ordering token (`order-token`)

A **monotonic, per-sender, best-effort** token used only for display ordering. Dedup is by **message-id** (§2), not by this token. Clients must tolerate out-of-order and duplicate delivery and a reply arriving before its target. **Unchanged in generation 2.**

**Form (fixed):**

```
order-token = ts-millis "-" sender-tag "-" seq      ; total 29 chars, lexicographically sortable
ts-millis   = b32hex-fixed( unix-millis, 11 )       ; 11 chars, big-endian, zero-left-padded
sender-tag  = b32lower( SHA-256(utf8(sender-identifier)) )[0:8]   ; 8 chars, [a-z2-7]
seq         = b32hex-fixed( per-sender-counter, 8 ) ; 8 chars
```

- `b32hex-fixed(n, w)` is RFC 4648 **Base32hex** (lowercase, alphabet `0123456789abcdefghijklmnopqrstuv`), big-endian, **left-zero-padded to exactly `w` characters**. Base32hex preserves numeric order under lexicographic string comparison, so plain string sort of the token = numeric sort of `(ts, sender, seq)`. 11 Base32hex chars hold 55 bits — enough for unix-millis until well past the year 10000.
- **`ts-millis`** — the sender's wall-clock time in milliseconds at send. Best-effort only (clocks drift/lie; no trusted time in MVP).
- **`sender-tag`** — a short, stable, filename-safe tag derived from the sender identifier. It is a **tie-breaker and a per-sender namespace**, not an identity proof (no verified identity key in MVP).
- **`seq`** — a strictly increasing **per-sender** counter, so two messages a single sender emits within the same millisecond still order deterministically and get distinct order-tokens. The counter is sender-local state; it need not be globally consistent.

**Tie-break rule (total order, deterministic for all readers):** sort ascending by the full message-id string. Because the order-token is the lexicographically-sortable prefix, this is equivalent to:
1. `ts-millis` ascending, then
2. `sender-tag` ascending (stable per-sender grouping for same-millisecond cross-sender ties), then
3. `seq` ascending, then
4. `content-hash` ascending (final deterministic tie-break for the pathological equal-prefix case).

Every reader computes the same order from the file names alone — no clock, no server time, no decryption needed for ordering. **The order-token is also the cursor coordinate** (§9): a member's stored cursor is an order-token (or full message-id) value, and "messages after the cursor" is the lexicographic-suffix of the `log/` listing.

**Causality:** a `reply-to` may reference the §2 file name of a message not yet delivered. The reader does **not** reorder or block on it — it shows the reply against an unknown/unloaded target and degrades gracefully (architecture → Ordering & causality). `reply-to` is a §2 file name carried inside the envelope plaintext (§5 / §8.4); it references *another* message and is distinct from this envelope's own file name (a message has no inner self-id, §8.6).

---

## 5. Message envelope framing

The file content **is** the AEAD ciphertext wrapped in a small, versioned binary frame. The transport feature wrote/read the whole post-header blob as opaque bytes; the **crypto feature** (`docs/features/crypto_plan.md`) pins the internal structure of that blob (nonce + AEAD ciphertext + tag) and the AAD binding. The frame is defined in full so crypto and compression implement it without changing the on-disk format. **Unchanged in generation 2** — the shared-log / change-index rework touches only the folder/poll layout, not the per-file framing.

**Serialization choice: a compact fixed binary header + binary body** (not JSON). Justification for a slow, rate-limited WebDAV transport: every byte is a round-trip cost and 429 pressure (decision 3); a JSON header would add quoting, field names, and Base64 of the binary body (~33% inflation). A fixed binary header is the smallest self-describing framing. The roster/chat descriptor files (§1.3) stay JSON because they are human-managed config, not per-message hot path.

**Envelope byte layout (big-endian):**

```
offset  size  field            value / meaning
------  ----  ---------------  -----------------------------------------------------
0       4     magic            ASCII "OWDM"  (0x4F 0x57 0x44 0x4D) — Open WebDAV Messenger
4       1     envelope-version 0x01  — this framing version (distinct from protocol version, §7)
5       1     codec-id         compression codec of the *plaintext before encryption*:
                                 0x00 = none
                                 0x01 = deflate (raw DEFLATE, RFC 1951, nowrap)
                                 [other values reserved — unknown codec = error path, §7]
6       1     flags            bit0..7 reserved, MUST be 0x00 in protocol v2
7       1     reserved         MUST be 0x00 (alignment / future use)
8       N     ciphertext-blob  AEAD blob: nonce(24) ‖ AEAD-ciphertext-with-tag  [filled by crypto feature]
```

The 8 bytes at offsets 0–7 are the **envelope header**; the bytes at offset 8 onward are the **ciphertext-blob** (§5.1).

- **Header size:** fixed **8 bytes**. `N` = (file length − 8) = the ciphertext blob length, derived from `d:getcontentlength` / the `GET` body length; no length field is stored (the file boundary is the length).
- **`magic` + `envelope-version`** let a reader reject a non-envelope or future-frame file before touching the blob.
- **`codec-id` — the compression slot (filled by the compression feature).** Records which codec compressed the plaintext **before** encryption (compress-then-encrypt, decision 4 / stack-notes Traffic compression). The reader inflates per this field *after* AEAD-open. **For the sync feature `codec-id` stays `0x00 (none)`** — no compression is wired by sync; the field's presence is what lets the compression feature turn it on without a format change. Unknown `codec-id` on read → **error path, not a guess** (skip/reject the message). Because the header is AEAD AAD (§5.1), `codec-id` is also tamper-evident.

### 5.1 Ciphertext-blob layout (filled by the crypto feature)

The bytes at offset 8 onward are the **ciphertext-blob**. The crypto feature (`docs/features/crypto_plan.md`, architecture decision 1 = libsodium-only) pins these now-fixed values:

```
ciphertext-blob = nonce(24 bytes) ‖ AEAD-ciphertext-with-tag
                  └─ 24-byte random nonce ─┘└─ ciphertext + 16-byte Poly1305 tag ─┘
```

- **AEAD = XChaCha20-Poly1305** (libsodium), via the combined-mode call `crypto_aead_xchacha20poly1305_ietf_encrypt` / `..._decrypt`. The Poly1305 authentication **tag is appended to the ciphertext by libsodium's combined mode** (16 bytes) — it is *not* a separate frame field; it lives at the tail of the AEAD-ciphertext portion of the blob.
- **Nonce = 24 bytes (192-bit), freshly random per seal,** placed at the **start** of the blob. XChaCha20-Poly1305's 192-bit nonce is large enough that a CSPRNG-random nonce is **safe to pick at random** (no counter/state needed; collision probability is negligible). A fresh random nonce per seal also makes two seals of identical plaintext produce different bytes, upholding the content-addressing invariant (§2).
- **AAD = the 8-byte envelope header.** The full 8 bytes at offsets 0–7 (`magic` ‖ `envelope-version` ‖ `codec-id` ‖ `flags` ‖ `reserved`) are bound as AEAD **associated data**. They are authenticated but **not** encrypted (a reader needs `magic`/version/`codec-id` *before* decrypting). Consequently `codec-id`, `flags`, `envelope-version` (and the rest of the header) **cannot be tampered without breaking the Poly1305 tag** → `open` returns a typed rejection.
- **Open / reject discipline:** `open` recomputes the tag over (nonce, header-as-AAD, ciphertext) and returns the exact original plaintext on success, or a **typed rejection** on any auth failure (wrong key, tampered header, tampered ciphertext/tag). It never crashes and never returns silently-wrong plaintext.
- **Truncated-blob guard:** a ciphertext-blob shorter than `nonce(24) + tag(16) = 40` bytes cannot be a valid sealed message. The reader treats it as a **reject / NotReady path** (skip this cycle, retry — consistent with §3 incomplete-write tolerance), **not** an index/bounds error. (For an empty plaintext the minimum valid blob is exactly 40 bytes: 24-byte nonce + 16-byte tag + 0-byte ciphertext.)
- **Key source is out of frame.** Which of the key sources (known / random / passphrase / DH — `docs/architecture.md` → decisions 9/10) produced the AEAD key is **not** encoded in the blob or header; it is determined by the chat's configuration (the chat taxonomy, architecture → Behavioral contract). The blob layout is identical for all sources.

- **Plaintext envelope fields** (chat-id, sender identity public key, optional reply-to, body, reaction, send-timestamp) live **inside** this encrypted plaintext, not in this outer frame. A message carries **no inner self-id** — its identity is its §2 file name (§8.6); `reply-to`/`target-id` carry other messages' §2 file names. Their exact structure — a versioned, signed, TLV message format with `kind` ∈ {text, reaction} — is pinned in **§8 (Message plaintext format)** of this document (authored by the `message-model` feature). The outer frame here carries only what a reader needs **before** decryption: magic, versions, codec-id.
- **Decompression bound (zip-bomb guard):** when the compression feature wires `codec-id = deflate`, the reader MUST bound the inflated size; exceeding the bound is an error path (architecture → Decompression bound). The numeric bound is fixed by the compression feature, not here.

---

## 6. WebDAV access rules this layout depends on

All cite `docs/stack-notes.md` → *OkHttp + WebDAV layer* (Last reviewed 2026-06-03).

- **List with `PROPFIND Depth: 1` — never `infinity`.** Two cheap listings drive a poll cycle (§9): one on `changes/<member-index-id>/` (this member's change index → which chats changed + from which cursor) and one on the changed chat's `log/` (the new message names after the cursor). Each is a single `PROPFIND Depth: 1` over a collection and its direct member files. A member whose change index is damaged falls back to a single `Depth: 1` on `log/` directly (§3). Servers MUST support Depth 0/1; infinity MAY be disabled (RFC 4918 §9.1). Source: stack-notes OkHttp/WebDAV "PROPFIND Depth header".
- **Capture the ETag from `d:getetag`** in each PROPFIND entry. The ETag identifies a specific resource version for conditional requests (RFC 4918 §8.6). Source: stack-notes OkHttp/WebDAV "Optimistic concurrency via ETags".
- **Conditional writes via `If-Match` only where a lost update is possible.** Source: stack-notes "Use the If / If-Match header with the resource's ETag". Concretely:
  - **Message-file `PUT`s (into `log/`) are NOT conditional.** Each message file is a **new, content-addressed** resource (§2/§3): a name collision means *identical content* (idempotent), and append-only forbids overwriting. There is no "lost update" to guard because no message file is ever updated. A `PUT` to an already-present `log/<message-id>` is a no-op success.
  - **Change-index entry `PUT`s (into `changes/<member-index-id>/`) are NOT conditional either**, by design (§9.2): each change entry is itself a **new content/cursor-addressed file** (its name encodes the cursor it advances to), so two senders notifying the same member produce two distinct entries, never a lost update on one mutable file. This is the deliberate "no rewrite-the-world, append-friendly" shape — a sender adds a small new entry, never reads-modifies-writes a per-member state file. (An alternative single-mutable-`cursor` file per member *would* need `If-Match`; §9.2 / the arch note explain why the append-entry shape is preferred precisely to avoid that lost-update round-trip.)
  - **`If-Match` is used only for mutable shared resources** — i.e. the §1.3 `meta/` files (`roster.json`, `chat.json`) when a later feature rewrites them. A `412 Precondition Failed` there is a **normal retry path** (re-`PROPFIND` for the new ETag, re-apply, re-`PUT`), not a crash. The transport exposes the conditional-`PUT` primitive (it existed since the transport feature) so the roster feature has it.
- **`MKCOL` to ensure folders.** Before first use the transport ensures `meta/`, `log/`, `changes/`, and each `changes/<member-index-id>/` exist via `MKCOL`; an already-exists response (`405 Method Not Allowed` / `301`) is handled **idempotently** (treat as success). Source: stack-notes "WebDAV verbs … MKCOL".
- **429 / timeout → exponential back-off.** Yandex.Disk rate-limits `PROPFIND` with `429 Too Many Requests` and is slow (it computes file hashes *after* upload, risking client timeouts). On `429` or a network timeout, back off exponentially and retry; do not abort the whole poll/write cycle (decision 3; plan scenarios *rate_limit_429_backs_off_and_retries*, *cursor_not_advanced_past_unfetched_on_backoff*). **A cycle interrupted by 429 mid-fetch MUST NOT advance its stored cursor past unfetched `log/` entries** — the cursor advances only over entries successfully fetched-and-persisted, so the next run resumes from exactly the first unfetched entry with no loss inside the window (§9.3). Source: stack-notes OkHttp/WebDAV "Yandex.Disk rate-limits PROPFIND".
- **Transport hygiene (cited):** one shared `OkHttpClient`; always close response bodies; no `!!` on WebDAV paths (Java-interop values are nullable); all WebDAV I/O on `Dispatchers.IO`. Source: stack-notes OkHttp + Kotlin components.

> **Out of scope here (MVP):** no Nextcloud `X-Requested-With` header, no `/public.php/dav` public-share endpoints, no NC-version quirks — generic WebDAV + Yandex.Disk only (Nextcloud is a separate plan). No WebDAV `LOCK` (ETag `If-Match` is the portable concurrency primitive — decision 3).

---

## 7. Versioning

Two independent version fields, so path layout and byte framing can evolve separately:

- **Protocol/layout version = `2`** (this document). Governs the §1 folder layout (shared `log/` + per-member `changes/` — **generation 2**, bumped from `1`'s per-recipient inbox tree by the sync feature, 2026-06-04), §2 message-id grammar, §3 append-only rule, §4 order-token form, §6 access rules, and §9 change-index/cursor semantics. Recorded on disk in `meta/chat.json` (`"protocol": 2`, §1.3) so a joining client can detect the layout generation before polling.
- **Envelope-version = `0x01`** (§5 byte 4). Governs the per-file binary frame independently of the path layout. **Unchanged** by the generation-2 rework — the envelope bytes are byte-for-byte identical to v1; only where the files live and how a poll discovers them changed.

> **Why a protocol bump (1 → 2) but not an envelope bump:** the rework changes the **path-layout generation** — the folder structure (`inbox/<recipient>/` → `log/` + `changes/<member>/`) and the poll mechanics (poll-own-inbox → read-change-index-then-fetch-log). That is exactly what the protocol version governs (§7 first bullet). The per-file framing (§5) and the inner message (§8) did not change a byte, so the envelope-version stays `0x01`. A v1 reader and a v2 reader **do not interoperate on the same chat-root** (different folder layout) — the `meta/chat.json` `protocol` value is how a client detects which generation a chat-root is and refuses a generation it does not implement (below).

**Unknown-version handling (error path, never a guess):**

- A reader that encounters a `meta/chat.json` with a **`protocol`** value it does not implement MUST refuse to operate on that chat-root (surface "unsupported protocol version"), rather than guessing the layout. (Where `meta/chat.json` is absent because no feature writes it yet, a generation-2 client assumes `protocol = 2` — the current generation it implements.)
- A reader that reads a message file whose **`magic`** ≠ `"OWDM"` or whose **`envelope-version`** it does not implement MUST skip/reject that file as not-understood — it MUST NOT attempt to parse the blob. Same rule for an unknown **`codec-id`** (§5). A blob too short to hold `nonce(24) + tag(16)` is likewise rejected (§5.1) — as not-ready/reject, not a bounds error.
- This "reject, don't guess" rule is what lets a future version add fields or change framing without a newer writer silently corrupting an older reader's view.


## 8. Message plaintext format (inside the §5.1 ciphertext-blob)

> **What this section is.** §5/§5.1 specify the **outer envelope framing** — `magic ‖ envelope-version ‖ codec-id ‖ flags ‖ reserved` then the `ciphertext-blob = nonce(24) ‖ AEAD-ciphertext-with-tag`. This section specifies the **plaintext that the AEAD seals/opens** — the structured bytes that, once `codec-id = 0x00` (no decompression) and AEAD-`open` have run, a reader deserializes into a typed message. It is the **interop contract the `message-model` feature implements** (`docs/features/message-model_plan.md`). Two app instances agree on a message's meaning iff they agree on this section. **Unchanged in generation 2** — the inner message format is independent of where the envelope lives on disk.
>
> **Relationship to the outer frame.** These bytes are exactly the plaintext fed to `Aead.seal` and returned by `Aead.open` (§5.1). They are **confidentiality-protected** (sealed inside the ciphertext-blob) and **integrity-protected as a whole** by the Poly1305 tag over (nonce, header-as-AAD, ciphertext). The per-message Ed25519 signature below is an **additional, in-plaintext** authenticator that distinguishes *which member* sent the message — something the shared AEAD key cannot do (§8.3).
>
> **Codec.** For this feature `codec-id` stays **`0x00` (none)** — the plaintext below is **not** compressed. Compression is a later feature that may set `codec-id = 0x01`; when it does, the bytes in this section are what gets DEFLATE'd before sealing and inflated after opening. Nothing in §8 changes when compression turns on.

### 8.1 Design rules

- **Versioned and forward-compatible.** A 1-byte `msg-format-version` leads every message. This is **independent** of the §7 protocol version and the §5 `envelope-version` — it governs only the plaintext structure in this section.
- **Reject-don't-guess (consistent with §7).** An unknown `msg-format-version` or an unknown `kind` tag, a length prefix that overruns the buffer, a trailing-bytes mismatch, a signature that does not verify, or an out-of-range enumerated value (e.g. a reaction index ∉ 0..4) is a **typed rejection** — the message is dropped, never partially parsed, never surfaced as valid, never a crash. A parser MUST validate every length prefix against the remaining buffer before reading (no index/bounds exception escapes to the caller — stack-notes → Kotlin null-safety/Java-interop: no `!!` on parse paths).
- **Extensible.** New `kind` tags and new trailing fields can be added under a future `msg-format-version` without breaking an older reader: the older reader sees an unknown version (or unknown kind) and rejects that one message, rather than mis-reading it.
- **Big-endian** for every multi-byte integer (same convention as §5, §0).

### 8.2 Common layout — TLV body under a fixed prefix

Every message, regardless of kind, is:

```
offset  size  field               value / meaning
------  ----  ------------------  ---------------------------------------------------
0       1     msg-format-version  0x01  — this plaintext-format version (§8.1)
1       1     kind                0x01 = text (§8.4), 0x02 = reaction (§8.5)
                                   [0x03..0x05 RESERVED: edit/delete/system — NOT defined now]
                                   [unknown kind = reject, §8.1]
2       32    sender-id-pubkey    sender's identity Ed25519 PUBLIC key (crypto_sign_PUBLICKEYBYTES = 32)
34      2     field-count         number of TLV fields that follow (big-endian uint16)
36      ...   fields              field-count TLV triples (§8.2.1), the SIGNED PAYLOAD
...     64    signature           Ed25519 detached signature (crypto_sign_BYTES = 64) over §8.3's byte range
```

- The **signed payload** is the contiguous byte range `[offset 0 .. start-of-signature)` — i.e. everything from `msg-format-version` through the last TLV field, **excluding** the trailing 64-byte `signature` itself (§8.3 pins this exactly).
- `sender-id-pubkey` is carried in the fixed prefix (not as a TLV) because it is mandatory for **every** kind and is the key the verifier checks the signature against.
- The `signature` is the **final 64 bytes** of the plaintext; the signed payload is therefore `plaintext[0 .. len-64)` and the signature is `plaintext[len-64 .. len)`. A plaintext shorter than `36 + 64 = 100` bytes (minimum prefix + zero fields + signature) cannot be valid → reject (not a bounds error).
- **No self-id in the prefix or the TLV body.** The fixed prefix carries no field naming the message itself, and **no `kind` requires a self message-id TLV** (§8.4 / §8.5): a message's identity is its §2 file name, assigned on seal (§8.6). The prefix and the 100-byte structural minimum above are therefore unchanged by that — the self-id was never part of the prefix.
- **Per-kind minimum valid sizes** (structural floor a parser may use; all are ≥ 100 bytes so the 100-byte check above always fires first). With the §8.2.1 TLV overhead of `1 (tag) + 2 (len) = 3` bytes per field, the smallest well-formed message of each kind is: a `text` with an empty body and no `reply-to` = prefix(36) + `chat-id` TLV(3 + len) + `body` TLV(3 + 0) + `send-timestamp` TLV(3 + 8) + signature(64); a `reaction` = prefix(36) + `chat-id` TLV(3 + len) + `target-id` TLV(3 + 62) + `reaction-index` TLV(3 + 1) + signature(64). (These depend on the `chat-id` length, still `[?]`; the constant `100`-byte floor is the only hard wire-level minimum.)

#### 8.2.1 TLV field encoding

Each field in the `fields` region is a TLV triple:

```
1 byte    field-tag      identifies the field (per-kind table, §8.4 / §8.5)
2 bytes   length         big-endian uint16 = byte length of value (0..65535)
length    value          the field bytes (encoding per field)
```

- **Tags are per-kind** (a tag number is interpreted in the context of the `kind` byte). A reader MUST read exactly `field-count` (prefix byte 34) triples and MUST end exactly at the start of the 64-byte signature; any overrun/underrun = reject.
- **Unknown field-tag within a known kind+version:** reject (this version's kind has a closed field set; new fields arrive under a new `msg-format-version`). This keeps "reject-don't-guess" total: a same-version message never carries a field this build does not know.
- **Optional fields** are simply absent (not present as a TLV). **Required fields** MUST be present exactly once; a missing required field or a duplicate tag = reject.
- **Integer fields** (e.g. timestamp, reaction-index) carry their value big-endian in `value` with the exact width stated per field.
- **String/text fields** (e.g. body) carry **UTF-8 bytes**, no NUL terminator (the `length` is authoritative); the body is **raw** — the Markdown subset is carried verbatim, no rendering, no normalization (rendering is the UI feature, decision 5).

### 8.3 Per-message Ed25519 signature — what it covers and why

- **Purpose — intra-chat sender authentication.** The AEAD key is **shared by every chat member** (decision 9 / Chat taxonomy: a chat key is one of known/random/passphrase, held by all members). AEAD therefore proves *a member of this chat* sealed the message, but **cannot distinguish which member** — any member could forge a message as another. The per-message Ed25519 signature closes this: each message is signed with the **sender's identity Ed25519 secret key** (decision 10 identity substrate), and carries the matching **public** key (`sender-id-pubkey`, §8.2). A member cannot sign as another member (they do not hold that member's Ed25519 secret), so impersonation is detected on verify.
- **Signed byte range (signer and verifier MUST agree exactly):** the signature covers `plaintext[0 .. len-64)` — the **entire serialized message from `msg-format-version` through the final TLV field, excluding the trailing 64-byte signature**. Concretely: `msg-format-version ‖ kind ‖ sender-id-pubkey ‖ field-count ‖ fields`. Because `sender-id-pubkey` is inside the signed range, the claimed sender key is itself signed (it cannot be swapped without breaking the signature).
- **Sign:** `crypto_sign_detached(signed-payload, sender_ed25519_sk)` → 64-byte detached signature, appended as the final field.
- **Verify (hard reject):** `crypto_sign_verify_detached(signature, signed-payload, sender-id-pubkey)`; libsodium returns `-1` on failure → **hard reject**, never best-effort (stack-notes → Crypto "verify with `crypto_sign_verify_detached` … treat -1 as a hard reject"). A `Rejected` result is also returned on: unknown version/kind, malformed/truncated buffer, bad length prefix, out-of-range enum, or a sender key the directory (a future feature) later distrusts — at *this* layer, only the cryptographic signature/structure is checked; **binding the key to a human is the directory/safety-number feature's job** (decision 10), not this one.
- **Confidentiality of the signature.** The whole signed message (including `sender-id-pubkey` and `signature`) lives **inside** the §5.1 ciphertext-blob, so the sender's identity public key and the signature are **also confidentiality-protected** — an on-disk observer (the disk operator) sees only AEAD ciphertext, not who signed.
- **Tamper-evidence.** Flipping any byte of the signed payload after signing breaks `verify_detached` → `Rejected`. (This is in addition to the outer Poly1305 tag and the §3 content-hash-in-name check, which already reject any post-seal byte flip.)

### 8.4 `text` message (kind = 0x01)

Carries a chat message body plus an optional reply reference. TLV field set (interpreted under `kind = 0x01`):

```
tag   field            req?  value encoding
----  ---------------  ----  ------------------------------------------------------------
0x01  chat-id          req   chat identifier the message belongs to. UTF-8; grammar is [?] in
                             architecture (chat-id grammar still open) — carried opaque here, length-
                             prefixed, validated against the chat-id grammar once that feature pins it.
0x02  reply-to         opt   the FULL §2 file name (order-token "~" content-hash) of the message this
                             text quotes — i.e. the content-addressed name of an already-received
                             message (§2 grammar, 62 bytes for a well-formed name, alphabet [0-9a-z~-]).
                             The reader validates the §2 grammar; a malformed value = reject. Absent =
                             not a reply. May reference an as-yet-undelivered target (§4 causality:
                             "a reply may precede its target") — the value is a valid string; whether it
                             resolves to a loaded message is the READER's concern, NOT a parse error.
0x03  body             req   UTF-8 text = plain text + the supported Markdown subset, RAW (no
                             rendering, no normalization). length is the UTF-8 byte count (0..65535).
0x04  send-timestamp   req   sender wall-clock at send, unix-millis, big-endian uint64 (8 bytes).
                             DISPLAY-ONLY / best-effort — NOT trusted, NOT used for ordering
                             (ordering is the outer §4 order-token). Clocks may drift/lie.
```

- **No self-id field.** A text message carries **no** inner "self" message-id: its identity **is** the §2 content-addressed file name of the envelope that seals it (§8.6), assigned at seal time. There is nothing inside the plaintext that names the message itself.
- `sender` (the author's public identity) is **not** a TLV here — it is the fixed-prefix `sender-id-pubkey` (§8.2), shared by all kinds.
- A `text` message with no `reply-to` round-trips with the field absent; with `reply-to` set it round-trips present (plan test `reply_to_optional`).

### 8.5 `reaction` message (kind = 0x02)

A reaction is its **own** message (not a field on a text message), carrying the target it reacts to and an index into the fixed 5-reaction set. TLV field set (interpreted under `kind = 0x02`):

```
tag   field            req?  value encoding
----  ---------------  ----  ------------------------------------------------------------
0x01  chat-id          req   chat identifier (same encoding as §8.4 tag 0x01).
0x02  target-id        req   the FULL §2 file name (order-token "~" content-hash) of the message being
                             reacted to — the content-addressed name of an already-received message
                             (§2 grammar; a malformed value = reject). May reference an as-yet-unseen
                             message (§4 causality) — applying a reaction to a missing target is a
                             sync/UI concern, NOT a parse error.
0x03  reaction-index   req   a single byte, value in the closed range 0..4 (the fixed 5-reaction set,
                             architecture → Reaction enum). An index ∉ 0..4 = REJECT (§8.1). The
                             concrete glyph for each index is a UI concern and is NOT fixed here.
```

- **No self-id field.** Like a text message (§8.4), a reaction carries **no** inner self message-id. The reaction is itself a content-addressed message, and its identity **is** its §2 file name (§8.6) — it does not name itself inside its own plaintext.
- `send-timestamp` is **not** carried on a reaction (a reaction's display position follows its target / its own §4 order-token; no display timestamp is needed). A future `msg-format-version` may add one as a new optional tag without breaking this version's readers.

### 8.6 Message identity: the §2 file name, no inner self-id

**A message has no inner self-id. Its identity IS its §2 content-addressed file name** (`order-token "~" content-hash`, §2), assigned at seal time. References to *other* messages carry those other messages' §2 file names. There is only **one** id space — the §2 file name — and the plaintext never duplicates it.

**Why no inner self-id (the fixed-point that forced this).** The §2 file name's `content-hash = b32lower(SHA-256(envelope-file-bytes))[0:32]` is computed over the **sealed** envelope bytes — which **encrypt** the plaintext. An inner field that had to equal the file name would therefore have to equal a SHA-256 taken over bytes that *include the encryption of that very field*: a self-referential fixed point over SHA-256 (you would have to invert the hash to choose a plaintext byte that makes its own ciphertext hash to a chosen value). It is **unsatisfiable**, not merely awkward. So the plaintext carries **no** field naming itself; identity is supplied entirely by the §2 name the transport assigns on seal.

- **Outer file name (§2)** = `order-token "~" content-hash`, content-addressed over the **whole envelope** (magic ‖ versions ‖ codec-id ‖ flags ‖ reserved ‖ nonce ‖ AEAD-ciphertext-with-tag). It is the message's id, the path on the disk, the dedup key, the idempotency key, and (via the `order-token` prefix) its ordering key and the cursor coordinate (§2, §3, §4, §9). **This is the message's only id.**
- **References use full §2 file names.** `reply-to` (§8.4) and `target-id` (§8.5) each carry the **full §2 file name** of an **already-received** message (the sender received those messages, so it knows their names). Content-addressed names are globally unambiguous, so a reference resolves directly to an on-disk path in `log/` — no second id space, no mapping table.
- **Graceful degradation (mirrors §4).** A reference may name a message the reader has not yet received (§4: "a reply may precede its target"). The reference is a **valid string**; whether it currently resolves to a loaded message is the **reader's** concern (sync/UI degrades gracefully), **not** a parse error. The parser only checks the value is a well-formed §2 name.
- **No "self ↔ name" cross-check exists** (there is nothing to cross-check — no inner self-id). The on-read integrity check that ties bytes to name is the **§3 content-hash check** (`b32lower(SHA-256(file-bytes))[0:32]` must equal the name's `content-hash` suffix), plus the outer Poly1305 tag (§5.1) and the §8.3 Ed25519 signature. These three already reject any post-seal byte flip and authenticate the sender; the removed inner self-id added nothing they do not already provide.

> **Why not put the signature in the content-hash only?** The §3 content-hash detects *any* byte change but cannot say *who* wrote the bytes (any member can compute a valid content-hash for a forged message). The §8.3 Ed25519 signature is the layer that authenticates the **sender within the shared-key chat** — orthogonal to, and additional to, content-addressing and AEAD.

---

## 9. Per-user change index + cursor semantics (generation 2)

> **What this section is.** The generation-2 read-efficiency layer. It lets one cheap poll tell a member **which chats changed for them and from which cursor**, so the member fetches only the new entries from each changed chat's shared `log/` (§1.2) — achieving the "aggregated changeset the PM asked for at bootstrap" **without** copying full messages N times (the v1 fan-out's write storm). The change index is an **optimization over the shared log, not a source of truth** (§3): a member can always rebuild its state by scanning `log/` directly.

### 9.1 What a sender writes (send round-trips)

On send, a sender:

1. **Writes the message once** to `log/<message-id>` (§1.2/§3) — a single non-conditional content-addressed `PUT` (idempotent on the §2 name). **One write, regardless of member count** (the generation-2 win over v1's N copies).
2. **Writes one small change entry** into **each other member's** change index `changes/<member-index-id>/` (§9.2) — the member set is the roster, **supplied as input** (out-of-band / from config in the sync feature, §1.3; a later roster feature manages it). The sender computes each `member-index-id` deterministically (§1.2) and `PUT`s one tiny entry per member. These are small (a cursor coordinate, not the message) and append-friendly (a new file, never a rewrite).

So a send to an `M`-member chat is **1 log write + (M−1) tiny change-entry writes** (the sender does not notify itself). The change-entry writes are bytes-tiny (≤ ~62 chars of cursor coordinate, §9.2) — far below the v1 cost of `M−1` **full-message** copies. Send tolerates partial failure: each write is independently retry-safe and idempotent (re-running a send re-`PUT`s the same `log/` file as a no-op and re-`PUT`s the same change entries as no-ops), so a 429/timeout part-way through is resumed by simply re-running (no torn state — there is no multi-file transaction to half-commit).

### 9.2 Change-entry format (cursor marker)

A change entry is a **tiny, content/cursor-addressed file** in `changes/<member-index-id>/`. Its **name encodes the cursor coordinate it advances the member to** for a given chat; it carries **no message body**. Recommended entry name (the coder pins the exact encoding; this is the shape):

```
change-entry-name = chat-tag "~" order-token
chat-tag          = b32lower( SHA-256(utf8(chat-id)) )[0:16]   ; 16 chars, [a-z2-7] — which chat changed
order-token       = the §4 order-token of the message just written to log/ (29 chars) — the new cursor coordinate
```

- **`chat-tag`** identifies *which chat* changed for this member (a member's change index spans all chats they belong to on this disk; the tag groups entries by chat). It is a hash of the chat-id, filename-safe and fixed-length (same discipline as §1.2). The reader maps the tag back to a chat via its local roster of joined chats.
- **`order-token`** (the §4 sortable token of the just-written message) is the **cursor coordinate**: it tells the member "chat `<chat-tag>` now has content at least up to this order-token". The member compares it to its stored per-chat cursor; if newer, it fetches `log/` entries between its cursor and this coordinate (§9.3).
- **Append-friendly, no lost update (why no `If-Match`):** because the entry name encodes the coordinate, two senders notifying the same member about the same chat at different times produce two **distinct** entries (different order-tokens) — never a competing rewrite of one mutable file. A reader simply takes the **maximum** order-token among a chat's entries as the high-water mark. This is the deliberate shape that keeps the send write cheap and unconditional (§6) and avoids the read-modify-write round-trip a single mutable per-member cursor file would force.
- **Plaintext metadata, by design (no secret on disk).** A change entry exposes only what the shared `log/` file names **already** expose to the disk operator: the chat-tag (a hash of the chat-id) and an order-token (which itself embeds a millisecond timestamp + a sender-tag hash + a per-sender sequence, §4). It carries **no** AEAD key, no passphrase, no message body, no plaintext (Security constraints / SC4). The operator already sees the `log/` file names and the `changes/` folder structure under the one shared credential; the change entry adds **no new secret**, only makes the per-member "what changed for whom, when" association explicit on disk. **Metadata this exposes to the operator** (named here for the later threat-model rows — not authored here): (a) **per-member activity** — which members' change indices are being written to, and how often (a coarse who-is-active signal); (b) **per-chat change timing/volume** — when and how often each chat (by chat-tag) gets new messages; (c) **membership shape** — the count and identity-hashes of members (the set of `member-index-id` folders) and which chats they share (which `chat-tag`s appear in whose index). This is the same class of metadata the v1 inbox layout leaked (folder-per-recipient, file-per-message) — **not a new secret-exposure surface**, but it is the surface the threat-model's metadata rows name. **No message content is ever exposed** (bodies live only as AEAD ciphertext in `log/`).
- **Pruning of stale change entries is part of the deferred retention feature** (§1.4), not this one — like `log/`, the change index is not auto-trimmed by the sync feature. A reader tolerates redundant/old entries (it takes the max coordinate per chat-tag and dedups fetched messages by §2 message-id), so accumulated entries are a size concern for the future pruning feature, never a correctness problem.

### 9.3 Cursor semantics — resume, no loss, no double-surface

Each member stores, **locally** (in Room, never on disk), a **per-chat cursor** = the order-token coordinate up to which it has successfully fetched-and-persisted `log/` entries for that chat. A poll cycle:

1. **`PROPFIND Depth: 1` on `changes/<member-index-id>/`** (one cheap request) → the set of change entries → group by `chat-tag` → for each chat, the **max** order-token coordinate seen.
2. **For each chat whose max coordinate is newer than the stored cursor:** `PROPFIND Depth: 1` on that chat's `log/` (one request) → list message names → select the names whose order-token is **> the stored cursor and ≤ the new coordinate** (lexicographic compare on the §2 name, §4) → `GET` each (§3 on-read hash check, §5.1 AEAD-open, §8 parse+verify) → persist to Room → **advance the stored cursor only over entries successfully fetched-and-persisted.**
3. **Dedup** is by §2 message-id (§2/§3): a re-listed entry already in Room is a no-op (idempotent local insert), so re-running a cycle, or two cycles overlapping, never double-surfaces a message.

The properties the plan requires fall out of this:

- **Resume after an arbitrarily long gap (Doze/App-Standby — stack-notes WorkManager):** the cursor is durable local state; a cycle deferred for hours simply starts step 1 from the stored cursor on the next run and catches up everything still in the `log/` window. Nothing within the window is lost; the missed runs only delayed the catch-up, they did not skip it.
- **429/timeout mid-fetch does NOT advance the cursor past unfetched entries (§6):** the cursor advances only over `log/` entries that were fetched **and** persisted in step 2. If a `429`/timeout interrupts the cycle mid-fetch, the cycle backs off and the cursor still points before the first unfetched entry, so the next run re-fetches from exactly there — **no skip, no loss** inside the window (plan scenario *cursor_not_advanced_past_unfetched_on_backoff*).
- **No double-surfacing across cycles:** dedup by §2 message-id (step 3) makes a re-fetched entry idempotent — the same name → the same local row.
- **Index-damage fallback (§3):** if a member's change index is empty, tampered, or deleted, the member skips step 1 and does step 2's `log/` listing directly against its stored cursor — slower (it lists the whole `log/` rather than only changed chats) but **lossless** within the window. The shared `log/` is ground truth; the change index only saves the cost of the full listing.

---

## Cross-references

- `docs/architecture.md` → decision 2 (Disk topology / Topology A), decision 3 (Aggregated sync — **revised to shared-log + change-index + window** by the sync feature; the decision-3 text update is the post-coding handoff), decision 4 (Compression codec), decision 9 (Crypto substrate — AEAD/key sources that fill §5.1), decision 10 (Identity substrate — Ed25519/X25519), decision 11 (Message model — §8), and *Behavioral contract* (chat taxonomy, message-id/file-naming/ordering/codec invariants — this file is their authoritative source for the byte/path layout).
- `docs/stack-notes.md` → *OkHttp + WebDAV layer* (verbs, Depth, ETag/If-Match, 429), *WorkManager* (15-min floor + Doze deferral the §9 cursor absorbs), *Room* (local cursor + history), *Traffic compression* (compress-then-encrypt, codec-id), *Crypto library* (AEAD that fills the ciphertext-blob slot).
- `docs/features/webdav-transport_plan.md` — the feature that implemented §6 access rules, §2/§3 append-only + content-hash, and the v1 path layout.
- `docs/features/crypto_plan.md` — the feature that fills §5.1 (ciphertext-blob: nonce + XChaCha20-Poly1305 + tag, header as AAD).
- `docs/features/message-model_plan.md` — the feature that authors §8 (the inner signed message plaintext).
- `docs/features/sync_plan.md` — the feature that reworks §1 to generation 2 (shared `log/` + per-member `changes/`), authors §9 (change index + cursor), reserves §1.4 (retention window, pruning deferred), and implements send + the background poll cycle + local Room history.

*Authored 2026-06-03 for v2.9.0 (generation 1). Transport slots fixed; §5.1 crypto slot fixed by the crypto feature (2026-06-03); §8 message-plaintext format fixed by the message-model feature (2026-06-03). §8 corrected 2026-06-04: removed the unsatisfiable inner self-`message-id` field (a fixed point over SHA-256 of the sealed bytes). **Reworked 2026-06-04 to layout generation 2 (protocol version 1 → 2) by the sync feature: §1 folder layout (per-recipient `inbox/` fan-out → shared `log/` + per-member `changes/`), §3 inbox-bits → shared-log + flat-trust-degradation bits, §6 poll/write bits, and the new §9 (change-index entry format + cursor semantics) + §1.4 (retention window RESERVED, pruning DEFERRED). §2 message-id grammar, §4 order-token, §5/§5.1 framing + AEAD, §8 inner message, §7 envelope-version are byte-for-byte unchanged.*** §3 cursor-interaction clarification 2026-06-04 (sync code-review): refined §3's single incomplete/mismatch “not ready” bucket into the transient-block vs. permanent-resolve distinction the §9.3 cursor relies on (no loss vs. no wedge) — positional rule referenced from §9.3, not restated.
