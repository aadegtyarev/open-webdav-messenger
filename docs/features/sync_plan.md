# sync — plan

> The substrate that turns the four shipped substrates (transport, crypto, identity, message-model) into a working conversation: write a message so every chat member receives it, poll in the background, catch up after going offline, and keep full history locally. **Backend substrate — no UI** (the Compose chat surface is a later feature); behavior is exercised by tests, not a screen.

## Decisions taken in planning (PM, 2026-06-04)

- **Polling cadence (resolves OPEN architecture decision #6, the WorkManager side):** background polling via WorkManager `PeriodicWorkRequest`, clamped to the platform **~15-minute floor** (Android hard floor, not our choice — stack-notes WorkManager). No persistent notification. The faster foreground-service "instant delivery" mode is **deferred** to when UI exists to host its toggle (it stays OPEN decision #6's foreground side). Manual-only refresh was rejected (no background auto-catch-up).
- **Scope — core round-trip first:** this feature delivers the new on-disk layout + send + receive + offline catch-up + local Room history + the background poll loop. **Retention-window pruning (auto-deleting old messages from the shared disk log) is deferred** to a small follow-on feature; the window is reserved in the layout so pruning slots in with no format change. Until pruning lands, the shared disk log grows within the window concept but is not auto-trimmed.
- **On-disk layout rework (PM-decided at bootstrap, backlog 2026-06-03):** replace "per-recipient inbox + full-message fan-out" with **shared per-chat log (one copy per message) + per-user change index (what's new for me, from which cursor) + retention window**. This revises architecture decision #3 and reworks `docs/protocol/webdav-layout.md`. The *how* (file shapes, cursor semantics) is designed by `pm-architect` before coding; this plan fixes the behavior, not the byte layout.

## Scenarios

(System-subject — no UI yet. Each is a capability the engine gains, verified by tests.)

1. A member sends a message; it is written once to the chat's shared log on the disk, and every other member's change index is updated so their next poll surfaces it. The sender never overwrites an existing file (append-only, content-addressed — webdav-layout §2/§3).
2. A member's background poll cycle reads its own change index in one cheap request, learns which chats changed and from which cursor, fetches only the new envelopes from those chats' shared logs, and stores the received messages locally.
3. A received envelope is fully validated before it becomes a message: content-hash matches the file name (§3), AEAD-open succeeds (crypto), the inner Ed25519 signature verifies and the TLV parses (message-model). Any failure → the file is rejected/skipped, never surfaced, never crashes the cycle.
4. Messages are deduplicated by message-id (§2) and ordered by the order-token (§4); duplicate delivery across cycles is idempotent (same message-id → same local row), and a reply that arrives before its target is stored and degrades gracefully (not blocked, not an error).
5. A member offline longer than the poll interval catches up on return: the change index + shared-log window yield everything missed **within the window**, in order, deduplicated. Messages older than the window are unavailable from the disk (honest limit — full history still lives locally on devices that already had it).
6. The full conversation history is kept locally (Room), unbounded locally and independent of the disk window, so it is available offline and between polls.
7. The derived chat key is obtained once and held in memory for the cycle; decrypting many messages in one poll does **not** re-run the memory-hard Argon2id KDF per message (recorded downstream expectation, crypto review 2026-06-03).
8. The background poll loop tolerates the disk being slow/rate-limited: `429`/timeout mid-cycle backs off and retries (reusing the transport), and a cycle deferred by Doze/App-Standby resumes from its stored cursor on the next run with no message loss within the window.

## Existing behaviors this feature touches

(`docs/user-journeys.md` is currently a skeleton — this feature authors the first real journey; see Docs to update. The behaviors below are the shipped-substrate invariants sync must not break.)

- **Transport append-only + content-addressing (`webdav-transport`):** writers only `PUT` new content-addressed files, never overwrite; a reader rejects a file whose recomputed hash ≠ its name; `429`/timeout → exponential back-off; `PROPFIND Depth: 1`, never `infinity`; `MKCOL` idempotent. Sync reuses these verbs and rules unchanged.
- **Envelope framing + AEAD (`crypto`, webdav-layout §5/§5.1):** the on-disk file is `magic ‖ versions ‖ codec-id ‖ flags ‖ reserved ‖ nonce ‖ AEAD-ciphertext-with-tag`; `codec-id` stays `0x00`; AEAD-open returns a typed rejection on any auth failure. Sync seals/opens through this layer, does not re-implement it.
- **Inner message format + signature (`message-model`, webdav-layout §8):** the sealed plaintext is a versioned TLV message (`text`/`reaction`) with a per-message Ed25519 signature and no inner self-id (identity = §2 file name; `reply-to`/`target-id` are other messages' §2 file names). Sync serializes/parses through this layer.
- **Key sources + Keystore storage (`crypto`/`identity`):** the chat key comes from one of known/random/passphrase (or the future X25519 fourth source); keys are Keystore-wrapped, device-local, never written to the disk. Sync consumes a `ChatKey`/`ChatKeyStore.load()`, never derives or persists raw keys to the disk.
- **Chat taxonomy (architecture Behavioral contract):** `dm`/`group` × `public`/`private`; sync treats the chat config as input (which key source), does not change the taxonomy.

## Contracts

(Internal substrate seams — not a public/exported API. Exact signatures are the coder's call; these are the behavioral shapes.)

- **Send:** given a chat + a sealed-and-signed envelope's bytes (produced via message-model + crypto), place it in the chat's shared log and update each member's change index. Idempotent on the message-id; never overwrites; tolerant of partial failure (retry-safe).
- **Poll cycle (receive):** read this member's change index → resolve changed chats + cursors → fetch new envelopes → validate (hash/AEAD/signature/parse) → dedup by message-id → persist to Room → advance the stored cursor only past successfully-fetched entries. Returns a typed result (new-count / skipped / backed-off), never throws.
- **Local history (Room):** persist received/sent messages and per-chat sync cursors; expose observable, paged history for the future UI; all access off the main thread. Schema exported and migration-tested.
- **Member list source:** the roster (who to update change indices for) is supplied out-of-band / from config in this feature, consistent with `webdav-layout` §1.3 (a later roster/directory feature owns managing it). Sync reads it; it does not manage membership.

## Stack expectations touched

- **WorkManager:** "The minimum repeat interval that can be defined is 15 minutes … Requesting a shorter interval … WorkManager clamps it to the 15-min minimum." The poll loop is a `PeriodicWorkRequest` at the ≥15-min floor; "15 min is a floor, not a guarantee" (Doze/buckets defer further) — the cursor design must absorb skipped/late runs. Source: <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work>
- **WorkManager (test):** Worker logic gated by `androidx.work:work-testing` `TestDriver` under `./gradlew test`. Source: <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/integration-testing>
- **Room:** "To prevent queries from blocking the UI, Room does not allow database access on the main thread" — `suspend` DAOs + `Flow`; **never** `allowMainThreadQueries()`. Source: <https://developer.android.com/training/data-storage/room/async-queries>
- **Room (migration/schema):** "A schema version bump requires a `Migration`"; set `exportSchema = true` and check the JSON into VCS so migrations are reviewable/testable; migration tests run instrumented. Source: <https://developer.android.com/training/data-storage/room/migrating-db-versions>
- **Room (paging):** "an unbounded message query feeding the UI should be paged (Paging 3)" — history exposed paged, not whole-chat loads. Source: <https://developer.android.com/training/data-storage/room/accessing-data>
- **OkHttp + WebDAV:** `PROPFIND Depth: 1` (never `infinity`); capture `d:getetag`; message `PUT`s are non-conditional new content-addressed files; `429`/timeout → exponential back-off; `MKCOL` idempotent. Reused from transport. Source: <https://datatracker.ietf.org/doc/html/rfc4918#section-9.1>, <https://github.com/kopia/kopia/issues/88>
- **Crypto (no re-derivation):** the memory-hard Argon2id KDF runs off the UI thread and is **not** re-run per message — the cycle holds the derived `ChatKey` (via `ChatKeyStore.load()` / in-memory cache). Source: <https://doc.libsodium.org/password_hashing/default_phf> (Argon2id is intentionally slow/memory-heavy)
- **Integration contract — on-disk layout:** the shared-log + change-index + window structures and cursor semantics are added to `docs/protocol/webdav-layout.md` (the authoritative interop spec); `pm-plan-checker` blocks a sync implementation that does not reference it.

## Interaction scenarios

(Sync touches shared disk state, async background work, network I/O, timers, and Room — not isolated.)

- When a **send is in flight while a poll cycle reads** the same chat's shared log: both proceed; the poll sees either the pre- or post-write state, and the next cycle picks up anything missed via the cursor (append-only means no torn read of an existing file).
- When **two members write to the same chat log concurrently**: both land as distinct content-addressed files; neither overwrites the other (append-only, §3).
- When the **same message is delivered across two cycles** (re-listed): the second insert is idempotent on message-id; no duplicate local row, no duplicate surfaced.
- When a **reply/reaction references a not-yet-received target**: it is stored and shown against an unknown target; resolving it later is the reader's concern, not a parse/sync error (§4 causality).
- When a **`429`/timeout interrupts a cycle mid-fetch**: the cycle backs off and retries; the stored cursor is **not** advanced past unfetched entries, so the next run resumes without skipping messages.
- When a **GET returns a truncated body or a hash that does not match the name**: the file is treated as "not ready" — skipped this cycle, retried next (§3 incomplete-write tolerance), never surfaced as corruption.
- When a **tampered/forged file is in the log** (hash mismatch, AEAD-open fails, or signature fails verify): it is rejected and skipped; the cycle continues for the rest.
- When a **WorkManager run is deferred by Doze for hours**: the next run resumes from the stored cursor and catches up everything within the window; nothing within the window is lost.
- When a **member returns from a long offline** (> poll interval but < window): the change index + window yield all missed messages in order, deduplicated; older-than-window is unavailable from disk (honest limit).
- When a **poll decrypts many messages in one cycle**: the chat key is derived/loaded once and reused; Argon2id is not re-run per message.

## Test plan

- **Existing tests that must pass:** all existing JVM tests (transport, crypto, identity, message-model) — sync must not regress any substrate.
- **New tests (JVM, MockWebServer for WebDAV + Room in-memory + work-testing `TestDriver`):**
  - `send_writes_one_log_entry_and_updates_each_member_index`: given a chat with N members, when a message is sent, then one shared-log file is written and each member's change index reflects it (no per-member full-message copy).
  - `send_is_idempotent_on_message_id`: given the same envelope sent twice, when written, then the target file is a no-op success and no duplicate is produced.
  - `poll_reads_index_then_fetches_only_new`: given a change index with one new chat/cursor, when a cycle runs, then only that chat's new envelopes are GETed (not the whole disk).
  - `poll_dedupes_by_message_id`: given a message delivered in two consecutive cycles, when polled, then exactly one local row exists.
  - `poll_orders_by_order_token`: given messages with known order-tokens delivered out of arrival order, when read back, then local order = order-token sort (§4 tie-break).
  - `reply_to_unreceived_target_is_stored_not_rejected`: given a text with a `reply-to` naming an unfetched message, when polled, then it is stored and resolvable-later, not dropped.
  - `forged_or_tampered_file_rejected_cycle_continues`: given one file with a bad content-hash / failed AEAD / bad signature among good ones, when polled, then the bad one is skipped and the rest are stored.
  - `offline_catch_up_within_window`: given a member with an old cursor and several messages within the window, when a cycle runs, then all are fetched in order; given a cursor older than the window, then only within-window messages are available (documented loss).
  - `cursor_not_advanced_past_unfetched_on_backoff`: given a `429` mid-fetch (MockWebServer), when the cycle backs off, then the stored cursor still points before the unfetched entries.
  - `room_history_is_observable_and_offline`: given stored messages, when observed via the DAO `Flow`, then history is returned without network and off the main thread.
- **Interaction scenario tests (one per Interaction scenario above):**
  - `concurrent_send_during_poll_no_torn_read`, `two_writers_no_overwrite`, `duplicate_delivery_idempotent`, `unresolved_reference_graceful`, `backoff_preserves_cursor`, `incomplete_get_skipped_and_retried`, `tampered_file_skipped`, `doze_deferred_cycle_resumes_from_cursor`, `long_offline_catch_up_window_bounded`, `key_derived_once_per_cycle`.
- **Stack-spec tests (one per stack expectation, referencing the source URL in a comment):**
  - `periodic_request_clamped_to_15min_floor`: verifies the enqueued `PeriodicWorkRequest` interval is ≥ `MIN_PERIODIC_INTERVAL_MILLIS` (WorkManager floor) — not just self-consistent.
  - `room_dao_rejects_main_thread_access`: verifies DAO access is `suspend`/`Flow` and a main-thread query is not permitted (no `allowMainThreadQueries`).
  - `room_migration_tested`: schema export present + a migration test passes (instrumented under `connectedAndroidTest`).
  - `propfind_uses_depth_1`: verifies the poll PROPFIND sets `Depth: 1` (MockWebServer asserts the header) — never `infinity`.
  - `get_rejects_hash_mismatch`: verifies the §3 on-read content-hash check rejects a body whose hash ≠ name.
  - `chat_key_not_rederived_per_message`: injects a counting key-source/dispatcher and asserts the KDF runs once per cycle, not per message.

## Docs to update

- `docs/protocol/webdav-layout.md`: rework the on-disk model from per-recipient inbox fan-out to **shared per-chat log + per-user change index + reserved retention window**; define the new file shapes, the change-index entry format, and cursor semantics; keep the §2/§3 content-addressing + append-only rules and the §5/§8 framing unchanged. Authored by `pm-architect` (architectural rework of the authoritative interop spec) before coding.
- `docs/architecture.md`: revise **decision #3** (aggregated sync → shared-log + change-index + window; record what changed and why, what is superseded); update the **Behavioral contract → Inbox / file-naming invariants** to the new model; flip the **module map** rows `app/.../sync/` and `app/.../data/` to Implemented after coding. `pm-architect`.
- `docs/threat-model.md`: add/UPDATE Threat rows for the new at-rest and metadata surfaces — (a) decrypted message history at rest in the local Room DB on the device; (b) messages persisting on the shared disk for a retention window (longer operator exposure to ciphertext + size/timing metadata); (c) the per-user change index leaking per-member activity metadata to the operator; (d) the cooperative honest-client polling assumption (a member could poll faster, hurting the shared disk) recorded as an assumption, not an enforced guarantee. Wire each to an existing or new `SCn`. **Required** — security-bearing project, feature touches `### Security-relevant surfaces` (data-at-rest/storage, network/transport). Updated by `pm-architect` post-coding.
- `docs/user-journeys.md`: author the first real journey — a member sends a message, another receives it in the background, and a returning-from-offline member catches up within the window. `pm-legacy-reader`.
- `CLAUDE.md` Pipeline section: no new validator command — `androidx.work:work-testing` and Room in-memory tests run under the existing `./gradlew test`; Room migration tests run under the existing `./gradlew connectedAndroidTest`. (Room schema-export JSON is checked into VCS as a reviewable artifact, not a separate pipeline command.)

## Out of scope

- **Retention-window pruning** — auto-deleting old messages from the shared disk log by time/size. **Deferred to a follow-on feature** (PM scope decision, 2026-06-04). The window is *reserved* in the layout so pruning slots in without a format change; until then the shared log is not auto-trimmed. Separate plan because the pruning policy (window length, new-member "history from join vs full window") is its own product surface.
- **Foreground-service "instant delivery" mode** — the sub-15-min cadence path (persistent notification, Android 14+ FGS type). Deferred to when UI exists to host its opt-in toggle; it is the still-open foreground side of architecture decision #6. Separate plan: different platform surface (notification + FGS declaration) and a user-facing setting.
- **UI / Compose chat surface** — rendering the synced history, the composer, the polling-interval control. Separate feature; sync is the engine beneath it.
- **Compression** — `codec-id` stays `0x00`; wiring DEFLATE is the separate codec feature.
- **Roster / directory management** — sync reads the member list out-of-band/from config (webdav-layout §1.3); managing membership, encrypted directory, and host-governed polling floor are the directory/community features.
- **Sibling cadence modes (categorical):** the polling-delivery mode is one of {WorkManager background floor (chosen), foreground-service fast (deferred — separate platform surface + UI toggle), manual-only refresh (rejected — no background catch-up)}.
