# Sync — design notes

## Context

The `sync` feature (`docs/features/sync_plan.md`) turns the four shipped substrates (transport, crypto, identity, message-model) into a working conversation: send a message so every member receives it, poll in the background, catch up after offline, keep full history locally. It is **backend-only — no UI**.

It carries two structural choices that need deciding before coding:

1. **Where the new logic lives** across the existing module tree (`transport/`, `protocol/`, plus the two planned-but-empty modules `sync/` and `data/`), and how the seam between "WebDAV path mechanics", "poll-cycle orchestration", and "Room persistence" is drawn so each stays inside the project's file/function-size limits and the off-main-thread rule.
2. **The shape of the per-user change index on disk** (webdav-layout §9): the same notify-cost can be met by more than one on-disk structure, and the choice trades WebDAV round-trips against the flat-trust-degradation and Doze-deferral constraints. The on-disk *byte* decision is already pinned in `docs/protocol/webdav-layout.md` §9.2 (a cursor-coordinate-named append entry); this note records **why** that shape was chosen over the plausible alternative, so the coder implements against the reasoning, not just the spec.

The existing codebase already treats "the WebDAV path layout" as a category (`protocol/ChatPaths`, `protocol/MessageId`) and "WebDAV verbs + retry" as a category (`transport/`). Sync adds two new categories alongside them: "one poll cycle" and "local durable history + cursor". This is a genuine new axis of extension, which is why a design note is warranted rather than a "just add it" exit.

## Adjacent implementations

1. **`protocol/ChatPaths`** at `app/src/main/kotlin/org/openwebdav/messenger/protocol/ChatPaths.kt` — mints chat-root-relative paths for the §1 layout. Today it mints v1 `inbox/<recipient-inbox-id>/` and the content-addressed message path. It dispatches purely by *path-string construction* — pure functions, no I/O, no state, no event subscriptions. It is where the generation-2 rework lands its `log/` and `changes/<member-index-id>/` path minting (replacing `inbox(...)`). The `member-index-id` hash is the **same byte function** as the v1 `recipient-inbox-id` (`MessageId.inboxId`), so the change is "point the same hash at a new folder", not a new primitive.

2. **`protocol/MessageId` + `protocol/OrderToken`** at `…/protocol/MessageId.kt`, `…/protocol/OrderToken.kt` — mint the §2 message-id (`order-token "~" content-hash`) and the §4 order-token. Pure, stateless, deterministic. These are **unchanged** by the rework (webdav-layout §2/§4 are explicitly kept) and become the *cursor coordinate* source for §9: the change-entry name and the stored cursor are both order-token values, so sync consumes `OrderToken` rather than introducing a parallel cursor type.

3. **`transport/WebDavTransport` + `transport/CallExecutor` + `transport/BackOff`** at `…/transport/` — the WebDAV verb layer: PROPFIND `Depth: 1`, MKCOL (idempotent), GET, PUT (conditional + non-conditional), DELETE, 429/timeout exponential back-off, single shared `OkHttpClient`, response-body closing, all on `Dispatchers.IO`. This dispatches by *HTTP verb*; it holds no protocol knowledge (it does not know what an inbox or a log is). Sync **reuses it unchanged** — send is `PUT`s, poll is `PROPFIND` + `GET`s, all already with back-off. The 429-mid-cycle back-off the plan needs is already here; sync's job is to **not advance the cursor** across a backed-off fetch, which is sync-layer state, not a transport concern.

4. **`crypto/MessageCrypto` + `keystore/ChatKeyStore` + `message/MessageParser`** — the seal/open + parse/verify pipeline. Sync calls these per fetched file (open → parse → verify) and once per cycle for the key (`ChatKeyStore.load()` holds the derived `ChatKey` so Argon2id is not re-run per message — backlog "Downstream expectations", plan scenario 7). These are **consumed, not extended**; the "derive once per cycle" rule is a sync-layer caching obligation, satisfied by loading the `ChatKey` at cycle start and passing it down.

## Behavioral risks in this area

Sync is the project's first **event-/timer-driven** module (everything before it was a pure library called synchronously). The risks are about **what triggers a write and whether a write can feed back into a read**:

- **Map of triggers → effects:**
  - WorkManager periodic tick → poll cycle → reads `changes/` + `log/`, writes **Room only** (received messages + advanced cursor). A poll cycle **never writes to the disk** — so a poll can never trigger another member's poll. No disk-write feedback loop on the read path.
  - User/app send → writes `log/` (1×) + `changes/` (M−1×) on the disk, and the sender's **own** Room (its own sent message). A send writes to *other* members' change indices, which their *next* poll reads — this is the intended one-way notify, not a loop (the sender does not write to its own change index, §9.1, so a send never notifies the sender's own next poll).
  - Room insert (received message) → may emit on a `Flow` the future UI observes. It does **not** trigger any disk or network I/O. The observable-history `Flow` is read-only downstream; no write path subscribes to it. **No feedback loop.**
- **The one real hazard: cursor advance across a partial fetch.** If the cursor were advanced to the change-entry's coordinate *before* the `log/` entries up to it were fetched-and-persisted, a 429/Doze interruption would strand the unfetched entries forever (the next cycle starts past them). The design forbids this (§9.3): the cursor advances **only over entries successfully persisted**. This is the single invariant the coder must not get wrong; the plan test `cursor_not_advanced_past_unfetched_on_backoff` gates it.
- **Concurrent writers / torn reads:** append-only + content-addressing (§3) means a poll mid-someone-else's-send sees either the pre- or post-write `log/` listing, never a torn existing file; the next cycle's cursor picks up anything missed. No lock needed (decision 3: no WebDAV `LOCK`).

## Variant A: change index as a cursor-coordinate-named **append log** (one tiny file per notify), `sync/` orchestrates over `transport/`, `data/` owns Room

- **Where:**
  - **`protocol/`** (existing) — extend `ChatPaths` with `log/` and `changes/<member-index-id>/` path minting + the §9.2 change-entry name (`chat-tag "~" order-token`); reuse `MessageId`/`OrderToken` unchanged. Pure path/string logic, no I/O — consistent with how `protocol/` is built today.
  - **`sync/`** (new) — the poll-cycle orchestrator + the WorkManager `PeriodicWorkRequest` (≥15-min floor). One `SyncEngine`-style seam with `send(chat, envelopeBytes, members)` and `pollCycle()`; the `CoroutineWorker` is a thin wrapper that calls `pollCycle()` and maps the result to `Result.success/retry`. Orchestration only — it composes `transport/` (verbs), `protocol/` (paths), `crypto/`+`message/` (open/parse/verify), and `data/` (persist). Holds no HTTP and no SQL itself, so it stays within the function-size limits.
  - **`data/`** (new) — Room: message entities + DAOs (`suspend`/`Flow`, never `allowMainThreadQueries`), per-chat cursor table, schema export (`exportSchema = true`, JSON in VCS), migration. Owns *all* persistence; `sync/` calls it, never touches SQL.
- **On-disk change-entry shape:** each notify is a **new tiny file** `changes/<member-index-id>/<chat-tag>~<order-token>` (§9.2). A reader takes the **max** order-token per chat-tag as the high-water mark.
- **Relation to adjacent:** **symmetric** with the existing append-only, content/coordinate-addressed discipline (§3) — the change entry is "just another append-only file whose name encodes its meaning", exactly like a `log/` message whose name encodes content+order. It reuses the same "new file, never rewrite" rule the transport already enforces.
- **Pros:**
  - **Cheapest, unconditional send write** (webdav-layout §6): each notify is a non-conditional `PUT` of a tiny file — no read-modify-write, no `If-Match`, no 412 retry loop. Two senders notifying the same member produce two distinct files, never a lost update.
  - **Best Doze/deferral behavior:** the reader takes the max coordinate, so an arbitrarily delayed cycle just reads whatever entries accumulated and computes the high-water mark in one pass — no ordering dependency between notifies.
  - **Flat-trust degradation is graceful (§3):** a deleted/tampered change index → fall back to a `log/` listing; the entries were never the source of truth.
  - **Symmetric with the codebase's existing append-only model** — lowest conceptual surface for the coder; reuses `transport/`'s PUT-new-file path verbatim.
- **Cons:**
  - **Change entries accumulate** (no per-notify cleanup) until the deferred pruning feature (§1.4) trims them. Tolerated by design (reader takes max + dedups by §2 id), but the `changes/` folder grows.
  - A poll's first PROPFIND on a long-lived index lists more entries than a single-cursor-file read would. Mitigated because entries are tiny and the listing is one `Depth: 1` call.
- **Risks:** the coder must implement "max coordinate per chat-tag", not "last entry wins" (entries are not ordered by arrival on disk). Spec'd in §9.2; worth an explicit test.

## Variant B: change index as a single mutable **per-member cursor file** rewritten on each notify (conditional `If-Match`)

- **Where:** identical module split (`protocol/` paths, `sync/` orchestration, `data/` Room) — only the on-disk change-index shape differs.
- **On-disk change-entry shape:** one mutable file per member, e.g. `changes/<member-index-id>/cursor.json`, holding `{ chat-tag: max-order-token }` for every chat. A sender **reads** the file (PROPFIND for ETag + GET), merges the new coordinate, and **conditionally PUTs** with `If-Match` (the §6 lost-update primitive). A reader reads the one file to learn all changed chats + cursors.
- **Relation to adjacent:** **asymmetric** to the existing append-only model — it reintroduces in-place mutation of a shared file, exactly the read-modify-write pattern the rest of the protocol deliberately avoids (decision 3 / §3). It is the same shape as the §1.3 `meta/` mutable files (which is why `If-Match` exists), but applied to the *hot send path*.
- **Pros:**
  - **Reader does one GET** of one small file to get all cursors — slightly fewer bytes than listing many entries, and bounded size regardless of notify history (no accumulation → no pruning pressure on `changes/`).
- **Cons:**
  - **Expensive send (the decisive cost against decision 3):** every notify becomes PROPFIND (ETag) → GET (current state) → conditional PUT, with a **412 retry loop** whenever two senders notify the same member concurrently. That is ~3× the round-trips per member per send, against a slow, 429-prone WebDAV — directly violating the "minimise round-trips, no rewrite-the-world" constraint the whole rework exists to satisfy.
  - **Lost-update surface on the hot path:** concurrent senders contend on one mutable file; under 429 back-off the 412-retry interacts badly with the rate limiter (retry storms).
  - **Worse flat-trust degradation:** a tampered single cursor file corrupts *all* of a member's chat cursors at once; the append-log loses at most the entries an attacker deletes, and the `log/` fallback heals either case — but B concentrates the blast radius into one file.
- **Risks:** the conditional-PUT/412 path is the most error-prone WebDAV interaction and now sits on every send, not just the rare roster rewrite.

## Recommendation

**Variant A** — the cursor-coordinate-named append entry with the `protocol/` (paths) → `sync/` (orchestration) → `data/` (Room) seam. It keeps the send write **cheap, unconditional, and lost-update-free** (the core reason for the rework — decision 3), is **symmetric** with the codebase's existing append-only content-addressed discipline (lowest surface for the coder and the next reader), and **degrades gracefully** under the flat-trust and Doze-deferral constraints because the shared `log/` remains the source of truth and the change index is a pure optimization. Variant B's only advantage (one bounded reader GET) does not pay for the ~3× send round-trips and the 412-retry contention it puts on the hot path of a rate-limited transport. This is the shape pinned in `docs/protocol/webdav-layout.md` §9.2; the coder implements against A.

**Note for the coder / plan:** the §1.2 `member-index-id` hash is byte-identical to the v1 `recipient-inbox-id` (`protocol/MessageId.inboxId`) — reuse that function, just point it at `changes/` instead of `inbox/`; do not mint a new hash. The plan does not need revision — its contracts (Send, Poll cycle, Local history, Member list source) already match this seam.
