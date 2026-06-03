# WebDAV on-disk protocol layout

> **Load-bearing interoperability contract.** This is the byte/path-level spec that every app instance reads and writes against. The **transport** feature (`docs/features/webdav-transport_plan.md`) implements the folder layout, file naming, append-only rule, and WebDAV access rules now. The **crypto** and **compression** features fill the marked opaque slots later. Two app instances interoperate if and only if they agree on this document.
>
> **Scope:** MVP, generic WebDAV (RFC 4918) + Yandex.Disk only. **No** Nextcloud-specific request shaping (separate plan), **no** scheduling/foreground-service, **no** multi-disk. Authoritative for the `[?]` invariants in `docs/architecture.md` → *Behavioral contract* (message-id grammar, inbox/file-naming, ordering token, envelope codec-id). Where this spec and the architecture's Behavioral contract overlap, **this file is the source of truth** and the architecture points here.
>
> **Trust model recap (decision 2, Topology A):** all members of a chat share **one** WebDAV credential (one app-password) scoped to one folder. To the provider they are the same disk identity. Every member can **read AND delete every file** in the shared space. Nothing below is an access-control boundary — the inbox split is a *read-efficiency* layout only.

**Protocol version:** `1` (see [§7 Versioning](#7-versioning)).
**Status:** transport slots fixed; crypto slot fixed (filled by the crypto feature); message-plaintext format fixed (§8, filled by the message-model feature); compression slot reserved (marked `filled by <feature>`).

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

One chat == one shared chat-root folder, reachable by one credential (decision 2).

```
<webdav-base-url>/<chat-root>/           # the one shared folder for this chat
├── meta/                                # chat metadata / roster (see §1.3)
│   ├── chat.json                        # chat descriptor: chat-id, chat-type, protocol-version  [populated by a later feature]
│   └── roster.json                      # member list (recipient identifiers)                     [populated by a later feature]
└── inbox/                               # parent of all per-recipient inboxes
    ├── <recipient-inbox-id>/            # one folder per recipient (see §1.2)
    │   ├── <message-id>                 # one file per message, content-addressed (§2, §3)
    │   └── <message-id>
    └── <recipient-inbox-id>/
        └── <message-id>
```

### 1.1 Chat-root

- The **chat-root** is the folder the credential is scoped to. Its path is supplied in the connection config (base URL + app-password + chat-root path) and is **never** written to the disk (it is local config, decision 2 / Security constraints).
- Directly under the chat-root live exactly two collections: **`meta/`** and **`inbox/`**. The transport ensures both exist with `MKCOL` (§6) before first use. No message files live directly under the chat-root.

### 1.2 Per-recipient inbox folders

- Every recipient has exactly one inbox at `inbox/<recipient-inbox-id>/`.
- **`<recipient-inbox-id>` derivation (deterministic, sender-computable):**

  ```
  recipient-inbox-id = b32lower( SHA-256( utf8(recipient-identifier) || 0x1F || utf8(chat-id) ) )[0:26]
  ```

  - `recipient-identifier` is the recipient's address/display identifier from the roster (the `sender`/address field of the envelope, §5; no verified identity key in MVP).
  - `0x1F` (ASCII Unit Separator) is a domain separator so `recipient="ab" + chat="c"` and `recipient="a" + chat="bc"` cannot collide.
  - `b32lower` is RFC 4648 Base32 in lowercase, no padding, alphabet `abcdefghijklmnopqrstuvwxyz234567`.
  - Truncated to **26 characters** (130 bits of the digest) — far beyond collision risk for a chat's membership, and a short, fixed-length, filename-safe segment.
- **Why a hash, not the raw identifier:** the raw recipient-identifier may contain characters outside the §0 alphabet (e.g. an email-like address or unicode display name). Hashing yields a fixed-length, always-filename-safe segment, and avoids leaking the raw identifier as a literal folder name on the disk. It is **deterministic** so any sender independently computes the same inbox path for a given recipient — that is how a sender **locates each recipient's inbox to fan out** (compute the id for every roster member, `PUT` into each).
- **This is not isolation.** Any member can list/read/delete any inbox (one credential). The split exists so a reader polls only its **own** inbox in one `PROPFIND Depth: 1` (decision 3), not the whole chat.

### 1.3 Chat metadata / roster (`meta/`)

Named **now** so the layout is stable, but **populated by a later feature** (roster/chat-management). The transport does not write these in this feature; it only ensures the folder exists.

- **`meta/chat.json`** — chat descriptor: `{ "protocol": 1, "chat-id": "<id>", "kind": "dm" | "group", "access": "public" | "private" }`. The chat taxonomy (the `kind` and `access` axes, and the optional password on private chats) is the **two-axis model** fixed in `docs/architecture.md` → Behavioral contract → *Chat taxonomy* (changing it is an architectural decision). This spec only reserves the path and JSON shape; the exact `chat.json` field names/shape are pinned by the later roster/chat-management feature against that taxonomy — keep this a **forward pointer**, do not restate the taxonomy here.
- **`meta/roster.json`** — member list: the recipient-identifiers a sender fans out to. Until a later feature writes this, membership is supplied out-of-band alongside the passphrase (decision 2).

> `meta/` carries **no secret** material (no passphrase, no key — Security constraints). Whether the roster itself is encrypted is the roster feature's call; this spec only reserves the path and the JSON shape.

---

## 2. message-id grammar

The message-id is **content-addressed** and doubles as the file name. Two concurrent writers never collide, and a duplicate send is idempotent: same id → same path → idempotent `PUT`.

**Grammar (fixed):**

```
message-id   = order-token "~" content-hash
order-token  = <see §4>                      ; 29 chars, lexicographically sortable, [0-9a-z-]
content-hash = b32lower( SHA-256(file-bytes) )[0:32]   ; 32 chars, [a-z2-7]
```

- **Separator:** a single `~` (tilde). `~` is unreserved in RFC 3986 paths, valid on Yandex.Disk and generic WebDAV, and does not appear in either the order-token alphabet (`[0-9a-z-]`) or the Base32 alphabet (`[a-z2-7]`), so the split point is unambiguous.
- **`content-hash`** is the Base32-lowercase (no padding) SHA-256 of the **exact file bytes** = the full message-envelope bytes as written to disk (§5: magic + version + header + ciphertext blob). It is computed over the bytes the writer `PUT`s, so any two writers producing identical envelope bytes produce the identical name → the same path → idempotent.
  - **Note for the crypto feature:** because the AEAD nonce is part of the envelope and is normally random, two *independent* encryptions of the "same" plaintext yield *different* bytes and therefore *different* ids — which is correct (they are different messages). True idempotency (a retried `PUT` of the *same* message) requires the writer to reuse the *same* envelope bytes on retry. The transport guarantees this: it computes the name from the finished bytes and retries the *same* bytes, never re-encrypts on retry.
- **Total length:** `29 + 1 + 32 = 62` characters — under every common WebDAV/filesystem name limit (255 bytes) with wide margin, even after the inbox path prefix.
- **Allowed character set:** `[0-9a-z~-]` only. Fully filename-safe per §0; no escaping needed in a WebDAV path.

**Why content-hash in the name (not just a random uuid):** it makes the name a function of content, giving free idempotency and letting a reader cheaply detect that two files in different inboxes are the *same* message (same `content-hash` suffix) for fan-out dedup — without opening (decrypting) them.

---

## 3. Inbox file-naming + append-only rule

- **One file per message.** The file name **is** the message-id (§2). There is no separate metadata file per message.
- **Append-only — writers only `PUT` new files; never modify or overwrite an existing one.** A writer that finds the target name already present treats the `PUT` as already-done (idempotent success), not as a conflict. A writer **never** issues a `PUT` that replaces existing content at a message path, and **never** `PROPPATCH`/`MOVE`/`COPY`s a message file.
- **Why append-only (compensation for the flat trust model):** under one shared credential (decision 2) there is no per-author protection. Content-addressing + (later) AEAD gives **tamper-detection of content** — if a byte changes, the content-hash in the name no longer matches `SHA-256(file-bytes)`, so a reader detects the mismatch and rejects the file. Append-only means no member silently rewrites another's message *in place*. **This does not give deletion resistance** — any member can still `DELETE` any file; deletion resistance (signatures, witnessed logs) is explicitly future work (architecture → Security constraints, "Flat trust model").
- **Reader integrity check (required):** on `GET`, a reader recomputes `b32lower(SHA-256(file-bytes))[0:32]` and compares it to the `content-hash` suffix of the file name. **Mismatch → reject the file** (treat as tampered/corrupt; skip, do not surface as a message). This is the on-read enforcement of content-addressing and is independent of (and additional to) AEAD authentication, which the crypto feature adds inside the blob.
- **Incomplete-write tolerance (transport interaction scenario):** a `GET` that returns a truncated/failed body, or whose recomputed hash does not match the name, is treated as **"not ready"** — skipped this cycle, retried on the next `PROPFIND`/`GET`. Never surfaced as corruption (matches plan Interaction scenario *reader_skips_incomplete_file*).

---

## 4. Ordering token (`order-token`)

A **monotonic, per-sender, best-effort** token used only for display ordering. Dedup is by **message-id** (§2), not by this token. Clients must tolerate out-of-order and duplicate delivery and a reply arriving before its target.

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

Every reader computes the same order from the file names alone — no clock, no server time, no decryption needed for ordering.

**Causality:** a `reply-to` may reference the §2 file name of a message not yet delivered. The reader does **not** reorder or block on it — it shows the reply against an unknown/unloaded target and degrades gracefully (architecture → Ordering & causality). `reply-to` is a §2 file name carried inside the envelope plaintext (§5 / §8.4); it references *another* message and is distinct from this envelope's own file name (a message has no inner self-id, §8.6).

---

## 5. Message envelope framing

The file content **is** the AEAD ciphertext wrapped in a small, versioned binary frame. The transport feature wrote/read the whole post-header blob as opaque bytes; the **crypto feature** (`docs/features/crypto_plan.md`) now pins the internal structure of that blob (nonce + AEAD ciphertext + tag) and the AAD binding. The frame is defined in full so crypto and compression implement it without changing the on-disk format.

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
6       1     flags            bit0..7 reserved, MUST be 0x00 in protocol v1
7       1     reserved         MUST be 0x00 (alignment / future use)
8       N     ciphertext-blob  AEAD blob: nonce(24) ‖ AEAD-ciphertext-with-tag  [filled by crypto feature]
```

The 8 bytes at offsets 0–7 are the **envelope header**; the bytes at offset 8 onward are the **ciphertext-blob** (§5.1).

- **Header size:** fixed **8 bytes**. `N` = (file length − 8) = the ciphertext blob length, derived from `d:getcontentlength` / the `GET` body length; no length field is stored (the file boundary is the length).
- **`magic` + `envelope-version`** let a reader reject a non-envelope or future-frame file before touching the blob.
- **`codec-id` — the compression slot (filled by the compression feature).** Records which codec compressed the plaintext **before** encryption (compress-then-encrypt, decision 4 / stack-notes Traffic compression). The reader inflates per this field *after* AEAD-open. **For the crypto feature `codec-id` stays `0x00 (none)`** — no compression is wired by crypto; the field's presence is what lets the compression feature turn it on without a format change. Unknown `codec-id` on read → **error path, not a guess** (skip/reject the message). Because the header is AEAD AAD (§5.1), `codec-id` is also tamper-evident.

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
- **Key source is out of frame.** Which of the three key sources (known / random / passphrase — `docs/architecture.md` → decision "Crypto substrate") produced the AEAD key is **not** encoded in the blob or header; it is determined by the chat's configuration (the chat taxonomy, architecture → Behavioral contract). The blob layout is identical for all three.

- **Plaintext envelope fields** (chat-id, sender identity public key, optional reply-to, body, reaction, send-timestamp) live **inside** this encrypted plaintext, not in this outer frame. A message carries **no inner self-id** — its identity is its §2 file name (§8.6); `reply-to`/`target-id` carry other messages' §2 file names. Their exact structure — a versioned, signed, TLV message format with `kind` ∈ {text, reaction} — is pinned in **§8 (Message plaintext format)** of this document (authored by the `message-model` feature). The outer frame here carries only what a reader needs **before** decryption: magic, versions, codec-id.
- **Decompression bound (zip-bomb guard):** when the compression feature wires `codec-id = deflate`, the reader MUST bound the inflated size; exceeding the bound is an error path (architecture → Decompression bound). The numeric bound is fixed by the compression feature, not here.

---

## 6. WebDAV access rules this layout depends on

All cite `docs/stack-notes.md` → *OkHttp + WebDAV layer* (Last reviewed 2026-06-03).

- **List an inbox with `PROPFIND Depth: 1` — never `infinity`.** One round-trip lists `inbox/<recipient-inbox-id>/` and its direct member files. Servers MUST support Depth 0/1; infinity MAY be disabled (RFC 4918 §9.1). Source: stack-notes OkHttp/WebDAV "PROPFIND Depth header".
- **Capture the ETag from `d:getetag`** in each PROPFIND entry. The ETag identifies a specific resource version for conditional requests (RFC 4918 §8.6). Source: stack-notes OkHttp/WebDAV "Optimistic concurrency via ETags".
- **Conditional writes via `If-Match` only where a lost update is possible.** Source: stack-notes "Use the If / If-Match header with the resource's ETag". Concretely:
  - **Message-file `PUT`s are NOT conditional.** Each message file is a **new, content-addressed** resource (§2/§3): a name collision means *identical content* (idempotent), and append-only forbids overwriting. There is no "lost update" to guard because no message file is ever updated. A `PUT` to an already-present message path is a no-op success.
  - **`If-Match` is used only for mutable shared resources** — i.e. the §1.3 `meta/` files (`roster.json`, `chat.json`) when a later feature rewrites them. A `412 Precondition Failed` there is a **normal retry path** (re-`PROPFIND` for the new ETag, re-apply, re-`PUT`), not a crash (plan scenario *conflict_412_retry_path*). The transport exposes the conditional-`PUT` primitive now even though message writes don't use it, so the roster feature has it.
- **`MKCOL` to ensure folders.** Before first use the transport ensures `meta/`, `inbox/`, and each `inbox/<recipient-inbox-id>/` exist via `MKCOL`; an already-exists response (`405 Method Not Allowed` / `301`) is handled **idempotently** (treat as success). Source: stack-notes "WebDAV verbs … MKCOL".
- **429 / timeout → exponential back-off.** Yandex.Disk rate-limits `PROPFIND` with `429 Too Many Requests` and is slow (it computes file hashes *after* upload, risking client timeouts). On `429` or a network timeout, back off exponentially and retry; do not abort the whole poll/write cycle (decision 3; plan scenarios *rate_limit_429_backs_off_and_retries*, *timeout_backs_off_and_retries*). Source: stack-notes OkHttp/WebDAV "Yandex.Disk rate-limits PROPFIND".
- **Transport hygiene (cited):** one shared `OkHttpClient`; always close response bodies; no `!!` on WebDAV paths (Java-interop values are nullable); all WebDAV I/O on `Dispatchers.IO`. Source: stack-notes OkHttp + Kotlin components.

> **Out of scope here (MVP):** no Nextcloud `X-Requested-With` header, no `/public.php/dav` public-share endpoints, no NC-version quirks — generic WebDAV + Yandex.Disk only (Nextcloud is a separate plan). No WebDAV `LOCK` (ETag `If-Match` is the portable concurrency primitive — decision 3).

---

## 7. Versioning

Two independent version fields, so path layout and byte framing can evolve separately:

- **Protocol/layout version = `1`** (this document). Governs the §1 folder layout, §2 message-id grammar, §3 append-only rule, §4 order-token form, and §6 access rules. Recorded on disk in `meta/chat.json` (`"protocol": 1`, §1.3) so a joining client can detect the layout generation before polling.
- **Envelope-version = `0x01`** (§5 byte 4). Governs the per-file binary frame independently of the path layout.

**Unknown-version handling (error path, never a guess):**

- A reader that encounters a `meta/chat.json` with a **`protocol`** value it does not implement MUST refuse to operate on that chat-root (surface "unsupported protocol version"), rather than guessing the layout. (For the transport feature, where `meta/chat.json` may be absent because no feature writes it yet, the transport assumes `protocol = 1`.)
- A reader that reads a message file whose **`magic`** ≠ `"OWDM"` or whose **`envelope-version`** it does not implement MUST skip/reject that file as not-understood — it MUST NOT attempt to parse the blob. Same rule for an unknown **`codec-id`** (§5). A blob too short to hold `nonce(24) + tag(16)` is likewise rejected (§5.1) — as not-ready/reject, not a bounds error.
- This "reject, don't guess" rule is what lets a future version add fields or change framing without a newer writer silently corrupting an older reader's view.


## 8. Message plaintext format (inside the §5.1 ciphertext-blob)

> **What this section is.** §5/§5.1 specify the **outer envelope framing** — `magic ‖ envelope-version ‖ codec-id ‖ flags ‖ reserved` then the `ciphertext-blob = nonce(24) ‖ AEAD-ciphertext-with-tag`. This section specifies the **plaintext that the AEAD seals/opens** — the structured bytes that, once `codec-id = 0x00` (no decompression) and AEAD-`open` have run, a reader deserializes into a typed message. It is the **interop contract the `message-model` feature implements** (`docs/features/message-model_plan.md`). Two app instances agree on a message's meaning iff they agree on this section.
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

- **Outer file name (§2)** = `order-token "~" content-hash`, content-addressed over the **whole envelope** (magic ‖ versions ‖ codec-id ‖ flags ‖ reserved ‖ nonce ‖ AEAD-ciphertext-with-tag). It is the message's id, the path on the disk, the dedup key, the idempotency key, and (via the `order-token` prefix) its ordering key (§2, §3, §4). **This is the message's only id.**
- **References use full §2 file names.** `reply-to` (§8.4) and `target-id` (§8.5) each carry the **full §2 file name** of an **already-received** message (the sender received those messages, so it knows their names). Content-addressed names are globally unambiguous, so a reference resolves directly to an on-disk path — no second id space, no mapping table.
- **Graceful degradation (mirrors §4).** A reference may name a message the reader has not yet received (§4: "a reply may precede its target"). The reference is a **valid string**; whether it currently resolves to a loaded message is the **reader's** concern (sync/UI degrades gracefully), **not** a parse error. The parser only checks the value is a well-formed §2 name.
- **No "self ↔ name" cross-check exists** (there is nothing to cross-check — no inner self-id). The on-read integrity check that ties bytes to name is the **§3 content-hash check** (`b32lower(SHA-256(file-bytes))[0:32]` must equal the name's `content-hash` suffix), plus the outer Poly1305 tag (§5.1) and the §8.3 Ed25519 signature. These three already reject any post-seal byte flip and authenticate the sender; the removed inner self-id added nothing they do not already provide.

> **Why not put the signature in the content-hash only?** The §3 content-hash detects *any* byte change but cannot say *who* wrote the bytes (any member can compute a valid content-hash for a forged message). The §8.3 Ed25519 signature is the layer that authenticates the **sender within the shared-key chat** — orthogonal to, and additional to, content-addressing and AEAD.

---

## Cross-references

- `docs/architecture.md` → decision 2 (Disk topology / Topology A), decision 3 (Aggregated sync), decision 4 (Compression codec), decision "Crypto substrate" (AEAD/key sources that fill §5.1), and *Behavioral contract* (chat taxonomy, message-id/inbox/ordering/codec invariants — this file is their authoritative source for the byte/path layout).
- `docs/stack-notes.md` → *OkHttp + WebDAV layer* (verbs, Depth, ETag/If-Match, 429), *Traffic compression* (compress-then-encrypt, codec-id), *Crypto library* (AEAD that fills the ciphertext-blob slot).
- `docs/features/webdav-transport_plan.md` — the feature that implements §1, §2, §3 (append-only), §6.
- `docs/features/crypto_plan.md` — the feature that fills §5.1 (ciphertext-blob: nonce + XChaCha20-Poly1305 + tag, header as AAD).
- `docs/features/message-model_plan.md` — the feature that authors §8 (the inner signed message plaintext: versioned TLV, kind {text, reaction}, per-message Ed25519 signature) and implements serialize/sign/parse/verify; rides inside the §5.1 ciphertext-blob with `codec-id = 0x00`.

*Authored 2026-06-03 for v2.9.0. Transport slots fixed; §5.1 crypto slot fixed by the crypto feature (2026-06-03); §8 message-plaintext format fixed by the message-model feature (2026-06-03); compression slot reserved. §8 corrected 2026-06-04: removed the unsatisfiable inner self-`message-id` field (a fixed point over SHA-256 of the sealed bytes) — a message's identity is now solely its §2 file name, and `reply-to`/`target-id` carry other messages' full §2 file names (§8.4/§8.5/§8.6).*
