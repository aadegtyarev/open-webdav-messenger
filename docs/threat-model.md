# Threat Model

**Last reviewed:** 2026-06-04

What we protect, from whom, and how. Read by agents before planning any feature that touches a security-relevant surface (see `### Security-relevant surfaces` in `WORKFLOW.md`).

Depth matches project risk: this is a **security-bearing product** — its entire premise is that *the WebDAV disk is an untrusted server* (architecture decision 2 / Architectural constraints). The model is therefore a thorough page, not a textbook.

Owner: `pm-architect`. This is the **risk layer**; the enforceable rules live in `docs/architecture.md` `## Security constraints` (the **rule layer**), referenced from the Threats table below by stable `SCn` ID. `Last reviewed` is bumped whenever this document is drafted or updated; the auditor compares it against the merge date of the most recent security-touching feature to detect staleness.

Source of decisions: `docs/architecture.md` decisions #1–#11 (decision #3 revised 2026-06-04 to the shared-log + change-index + retention-window model by the `sync` feature) + `## Security constraints` (SC1–SC17); `docs/protocol/webdav-layout.md` v2 / layout generation 2 (trust-model recaps §0/§3/§5.1/§8/§9); `.ai-pm/backlog.md` (join-flow / rotation / retention / host-polling notes).

---

## Assets

What exists in this system that has value to an attacker or matters to users.

- **A1: Private message content** — the plaintext of private (DM and private-group) chats. The product exists to keep this hidden from the disk operator and from outsiders. **As of the `sync` feature this plaintext also persists device-local** in the Room history DB (unbounded, independent of the disk retention window — decision 3); that at-rest copy is app-private storage, never on the WebDAV disk (T16, SC17).
- **A2: Identity secret keys** — the per-user Ed25519 signing `sk` and X25519 box `sk` (decision 10). Compromise enables impersonation, forged rotations, and decryption of DH-keyed chats.
- **A3: Per-chat keys & passphrases** — the symmetric `ChatKey` (from passphrase / random / known / DH source) and any user passphrase. Compromise decrypts that chat's content within the retention window and locally.
- **A4: The shared WebDAV disk credential (app-password)** — the one credential per community/chat that authenticates every disk request (decision 2, Topology A). Compromise grants full read AND delete of the shared space (but **not** decryption — A1 stays sealed; SC3).
- **A5: Membership / social-graph metadata** — who is in which chat, who talks to whom, when, and message volume/timing/structure on disk. Deliberately **not** fully protected in the MVP (see non-goals).
- **A6: Message integrity & authorship** — the guarantee that a message's content is untampered and that it was authored by the claimed chat member (decision 11 signature; content-addressing).
- **A7: Availability of message delivery** — the ability for members to send and receive within the retention window. Bounded by a no-server, rate-limited, finite-retention design.

## Users and roles

Who legitimately uses the system and what they can access.

- **Community member** — holds the shared WebDAV disk credential (A4) plus the chat keys/passphrases (A3) for the chats they belong to. Can read, send, and (technically) delete any file in the shared space. Trust level: "knows the passphrase = insider" (flat trust, SC11).
- **Host / owner** — the member who owns the underlying Yandex account. Controls disk access and credential rotation (creates/deletes the actual app-password in Yandex ID; the per-member re-encryption is app-automated — backlog). Single point of control/trust, accepted for MVP.
- **Outsider** — anyone without the disk credential and without any chat key. Has no legitimate access; can only see what leaks at the network boundary or what a leaked invite/credential grants.

## Adversaries

Who might attack and why.

- **The disk operator (Yandex) — the PRIMARY adversary.** Honest-but-curious untrusted server: holds the WebDAV app-password (A4), sees every on-disk byte, every file name, every size and timestamp, and all write activity. The entire architecture is designed around the assumption that this party reads everything it stores. Motivation: profiling, content access, compelled disclosure.
- **Malicious / curious community insider** — a legitimate member who holds the shared credential and the chat keys for their own chats. Can read their own chats by design; may try to forge messages as another member, delete others' messages, or read chats they are not keyed into.
- **Outsider with a leaked invite / credential** — someone who obtained a bearer invite or the shared app-password out-of-band (invites are bearer tokens with no per-invite revocation on Topology A — backlog). Gains whatever the leaked material grants until rotation.
- **Network MITM** — an attacker on the path between device and disk, trying to capture the credential or ciphertext in transit, or tamper with traffic.
- **Casual attacker** — opportunistic, low-effort: a lost/found device, a shoulder-surf, an automated scan. Looks for plaintext at rest, logged secrets, default-weak config.

## Trust boundaries

Where data crosses a boundary — each one needs a defense.

| Boundary | From → To | Defense |
|---|---|---|
| Device ↔ WebDAV disk | app → untrusted cloud server | Only AEAD ciphertext + non-secret protocol files cross; keys/passphrases/plaintext NEVER cross (SC1, SC3, SC4, SC5). Transport over TLS only (SC13). Bounded reads + path-traversal rejection on every fetched/minted path (SC14, SC16). |
| App ↔ Android Keystore | app → device-local secure storage | All secret key material (chat keys, identity secret keys, passphrases) is Keystore-wrapped, device-local, never written to disk, never logged (SC4, SC5). |
| App ↔ local Room history DB | app → device-local app-private SQLite | Decrypted message history persists app-private (OS sandbox), never on the WebDAV disk, and carries no keys/passphrases (SC17; SC4/SC5). The DB file is **not** additionally app-encrypted at rest — protected on a locked device by OS storage encryption only; an unlocked in-hand device is a non-goal (T16). |
| Member ↔ member within a chat | one chat member → another, over the shared AEAD key | The shared key cannot distinguish members; per-message Ed25519 signature is the only thing that authenticates *which* member authored a message (SC15). Content-addressed append-only files resist silent in-place tampering (SC11). |
| Out-of-band invite channel | host → joining member (QR / string, off-disk) | Bearer secret (disk access + chat key) delivered outside the disk; never placed on the untrusted disk in the clear. Confidentiality rests on the chosen out-of-band channel; no per-invite revocation in MVP (non-goal). |

## Threats

Specific threats to this system. Each gets an ID for cross-referencing.

The **Mitigation** column references the enforceable rule in `docs/architecture.md` `## Security constraints` by its stable `SCn` ID (one-way, ID-keyed — the rule text lives there, not here, and is never copied in). The parenthetical prose is only a human hint; the `SCn` ID is the stable anchor that survives rewording of the rule.

| ID | Threat | Assets | Likelihood | Impact | Mitigation |
|---|---|---|---|---|---|
| T01 | Disk operator reads private message content directly off the disk | A1 | H | H | SC1 (E2E AEAD, ciphertext-only on disk), SC3 (app-password is not a content key) |
| T02 | Disk operator derives the content key from the app-password it holds | A1, A3, A4 | H | H | SC3 (content keys independent of disk credential), SC4 (keys Keystore-wrapped, never on disk) |
| T03 | Intra-chat impersonation — a member forges a message as another member under the shared key | A6 | M | H | SC15 (per-message Ed25519 signature, hard-reject on verify failure) |
| T04 | Tampering with on-disk message files (in-place mutation / corruption) | A6, A1 | M | M | SC1 (AEAD tamper-detection), SC15 (signature over plaintext), SC11 (append-only, content-addressed — recompute-hash-on-read) |
| T05 | Cleartext credential / ciphertext capture by a network MITM | A4, A1 | M | H | SC13 (TLS enforced, cleartext rejected) |
| T06 | Path traversal via a crafted reference (`reply-to`/`target-id`) or crafted chat-root escaping the chat folder | A1, A5, A7 | M | M | SC16 (filename-safe alphabet, grammar-validated, no `..`/`/`, reject not dereference) |
| T07 | DoS via an oversized GET or a crafted TLV field-count exhausting memory | A7 | M | M | SC14 (bounded reads: 1 MiB GET cap + field-count cap, typed reject) |
| T08 | Malicious Markdown in a decrypted message (HTML injection, remote-image tracking/SSRF beacon, auto-navigation) | A1, A5 | M | M | SC8 (no HTML, no remote images, scheme allowlist, no autolink, tap-only navigation) |
| T09 | Compression side-channel (CRIME/BREACH-class length leak) co-compressing a secret with attacker-influenced data | A1 | L | M | SC6 (per-message independent compression), SC7 (bounded decompression) |
| T10 | Sealed-box rotation forgery — a sender-unauthenticated sealed payload spoofs a key rotation | A2, A3, A4 | M | H | SC12 (sealed-box is sender-unauthenticated → MUST be Ed25519-signed), SC15 (Ed25519 detached signature establishes authorship) |
| T11 | Untrusted decompression bomb (zip-bomb) inflating to exhaust device memory | A7 | L | M | SC7 (decompression size bound, error path not crash) |
| T12 | Secret material leaking to disk or logs (passphrase, derived key, identity sk) | A2, A3 | M | H | SC4 (chat keys Keystore-wrapped, never on disk/log), SC5 (identity sk Keystore-wrapped, never on disk/log) |
| T13 | A member reads a chat they are not keyed into, by reading the shared disk space | A1 | M | M | SC1 (per-chat AEAD key — not keyed = ciphertext only), SC11 (inbox split is read-efficiency, not access control — confidentiality rests on the key) |
| T14 | User mistakes a public chat for a private one and sends a secret into a non-secret chat | A1 | M | M | SC2 (public chats explicitly NOT secret — UI warns and nudges to a private chat) |
| T15 | Hand-rolled or misused crypto introduces a break (nonce reuse, weak KDF, unverified tag) | A1, A2, A3 | L | H | SC9 (audited primitives only), SC1 (XChaCha20-Poly1305 AEAD), SC10 (no `!!` on crypto paths — null-safe boundary) |
| T16 | Decrypted message history readable at rest in the local Room DB on a lost / unlocked device | A1 | M | M | SC17 (device-local app-private storage, never on the WebDAV disk; full-DB-at-rest encryption is an **accepted limitation** — relies on OS device-lock + storage encryption, see non-goal *Physical access to an unlocked device*), SC4/SC5 (chat & identity keys stay Keystore-wrapped, never in the history DB) |
| T17 | Longer disk-operator exposure: messages persist on the shared disk for a retention window (ciphertext + size/timing metadata observable for the whole window, vs a delete-after-read model) | A1, A5 | M | M | SC1 (ciphertext only — content stays sealed for the whole window; the longer window exposes **no plaintext**), SC3 (app-password is not a content key). Size/timing **metadata** over the window is the accepted *Metadata visible to the disk operator* non-goal (A5), not a content leak |
| T18 | Per-member change index (`changes/<member-index-id>/`) leaks per-member activity, per-chat change timing/volume, and membership-shape metadata to the disk operator | A5 | M | L | SC1 (change entries carry **no** message body / key / plaintext — cursor coordinate only; content stays sealed in `log/`). Same metadata class as the v1 per-recipient inbox layout — **not a new secret-exposure surface**; the metadata itself is the accepted *Metadata visible to the disk operator* non-goal (A5) |
| T19 | A member polls faster than the (future) community polling floor, degrading the shared disk's 429 budget for everyone (cooperative honest-client assumption) | A7 | L | M | **No enforced mitigation — ASSUMPTION / non-goal.** The host-governed polling floor is honest-client only (over-polling is hard to attribute under one shared credential, SC11); a host-enforced floor is a future community feature. Recorded as the *Cooperative polling-floor enforcement* non-goal, not an enforced guarantee |

Likelihood and Impact: L / M / H

> **Which shipped feature realizes each threat's mitigation** is tracked one place — the `### Realized by — feature → SCn / Threat traceability` table in `docs/architecture.md` `## Security constraints` (added 2026-06-04, audit-2026-06-04 Note 1). That table maps each of the four merged substrate features (`webdav-transport`, `crypto`, `identity`, `message-model`) to the `SCn` constraints it realizes and the `Tnn` rows above it addresses. The link is one-way and ID-keyed — this threat-model is not edited per realizing feature; the realized-by mapping lives next to the constraint definitions to avoid duplication. A future security feature reads that table to see what a surface already defends before extending it.

## What we explicitly do NOT protect against

Be honest about out-of-scope threats — without this list, scope creeps. These are load-bearing for this project: each is a deliberate MVP boundary, not an oversight.

- **Metadata visible to the disk operator** — who is in which chat, who writes when, message counts, sizes, file/folder structure, and the resulting traffic-analysis. This is the same metadata class the generation-2 shared-log + per-member change-index layout exposes (per-member activity, per-chat change timing/volume, membership shape — **T18**) and that the retention window lengthens the exposure of (**T17**); the change index is **not** a new secret-exposure surface (it carries cursor coordinates, never content). Even an (future) encrypted directory still leaks structure/timing. Full metadata hiding (padding, cover traffic, name randomization) is a separate, larger concern, out of MVP (A5; backlog).
- **Message deletion by any member** — under one shared credential (Topology A, SC11) every member can `DELETE` any file. AEAD gives tamper-*detection* of content, not deletion resistance. Deletion resistance (signatures over a witnessed/replicated log) is future work.
- **Forward secrecy** — a leaked passphrase or chat key exposes that chat's history within the retention window (and locally, unbounded). The double-ratchet / per-message-key path is deferred (decision 9; backlog).
- **Individual invite revocation** — invites are bearer tokens; whoever scans one is a member forever. Removing one person requires the rotate-with-auto-replace mechanism (future, needs X25519 + host action); until then there is no per-invite revocation on Topology A (backlog).
- **A member offline longer than the retention window losing messages** — the disk holds only a bounded retention window (e.g. 2–4 weeks, configurable); a member offline beyond it loses the oldest messages. Unavoidable without a server and with finite disk (backlog retention model). The window also lengthens the disk operator's exposure to ciphertext + size/timing metadata vs a delete-after-read model — **content stays sealed throughout** (T17, SC1); only the metadata window grows. (Window pruning is a deferred follow-on; the `sync` feature only reserves the window — `docs/protocol/webdav-layout.md` §1.4.)
- **Cooperative polling-floor enforcement (T19)** — the host sets a community-wide minimum polling interval, but enforcement is honest-client: a member can over-poll and hurt the shared 429 budget. Over-polling is hard to attribute under one shared credential (SC11). This is a cooperative **assumption, not an enforced guarantee** — a host-governed floor is a future community feature (backlog host-polling-floor).
- **A trusted member reading their own chats** — by design, a keyed member reads the chats they belong to. This is the intended function, not a threat.
- **Physical access to an unlocked device** — once a device is unlocked, the local Room history (now the unbounded device-local plaintext store — decision 3) and in-memory keys are readable. The history DB is app-private and never on the WebDAV disk, but is **not** additionally app-encrypted at rest (T16, SC17); device-lock and OS-level storage encryption are the user's responsibility. We do not defend an unlocked, in-hand device; full at-rest DB encryption is candidate future hardening.
- **Nation-state / endpoint compromise** — a compromised OS, a malicious keyboard, a rooted device, or a state-level adversary subverting the Keystore/CSPRNG is out of scope. We trust the platform's Keystore and CSPRNG.

---

## Review

Bump the **Last reviewed** date at the top whenever this document is drafted or updated. Revisit this document when:

- A feature touches a `### Security-relevant surfaces` item (see `WORKFLOW.md`)
- New user role added
- Security incident occurs
