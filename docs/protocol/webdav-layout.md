# WebDAV on-disk protocol layout

> **Load-bearing interoperability contract.** This is the byte/path-level spec that every app instance reads and writes against. The **transport** feature (`docs/features/webdav-transport_plan.md`) implements the folder layout, file naming, append-only rule, and WebDAV access rules now. The **crypto** and **compression** features fill the marked opaque slots later. Two app instances interoperate if and only if they agree on this document.
>
> **Scope:** MVP, generic WebDAV (RFC 4918) + Yandex.Disk only. **No** Nextcloud-specific request shaping (separate plan), **no** scheduling/foreground-service, **no** multi-disk. Authoritative for the `[?]` invariants in `docs/architecture.md` → *Behavioral contract* (message-id grammar, inbox/file-naming, ordering token, envelope codec-id). Where this spec and the architecture's Behavioral contract overlap, **this file is the source of truth** and the architecture points here.
>
> **Trust model recap (decision 2, Topology A):** all members of a chat share **one** WebDAV credential (one app-password) scoped to one folder. To the provider they are the same disk identity. Every member can **read AND delete every file** in the shared space. Nothing below is an access-control boundary — the inbox split is a *read-efficiency* layout only.

**Protocol version:** `1` (see [§7 Versioning](#7-versioning)).
**Status:** transport slots fixed; crypto slot fixed (filled by the crypto feature); compression slot reserved (marked `filled by <feature>`).

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

**Causality:** a `reply-to` may reference a message-id not yet delivered. The reader does **not** reorder or block on it — it shows the reply against an unknown/unloaded target and degrades gracefully (architecture → Ordering & causality). `reply-to` lives inside the envelope plaintext (§5 / crypto feature), not in the file name.

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

- **Plaintext envelope fields** (message-id echo, chat-id, chat-type, sender, reply-to, reaction, body, ordering data) live **inside** the encrypted plaintext, not in this outer frame, and are specified by the crypto/message-model features against `docs/architecture.md` → *Message envelope (conceptual fields)*. The outer frame here carries only what a reader needs **before** decryption: magic, versions, codec-id.
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

---

## Cross-references

- `docs/architecture.md` → decision 2 (Disk topology / Topology A), decision 3 (Aggregated sync), decision 4 (Compression codec), decision "Crypto substrate" (AEAD/key sources that fill §5.1), and *Behavioral contract* (chat taxonomy, message-id/inbox/ordering/codec invariants — this file is their authoritative source for the byte/path layout).
- `docs/stack-notes.md` → *OkHttp + WebDAV layer* (verbs, Depth, ETag/If-Match, 429), *Traffic compression* (compress-then-encrypt, codec-id), *Crypto library* (AEAD that fills the ciphertext-blob slot).
- `docs/features/webdav-transport_plan.md` — the feature that implements §1, §2, §3 (append-only), §6.
- `docs/features/crypto_plan.md` — the feature that fills §5.1 (ciphertext-blob: nonce + XChaCha20-Poly1305 + tag, header as AAD).

*Authored 2026-06-03 for v2.9.0. Transport slots fixed; §5.1 crypto slot fixed by the crypto feature (2026-06-03); compression slot reserved.*
