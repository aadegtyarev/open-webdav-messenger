# sync — plan compliance review (Pass 1)

Reviewer: pm-plan-checker. Date: 2026-06-04. Branch: `feature/sync`. Commits `91e4117..HEAD` (`fc9ef3b` feat, `6b13538` test, `766e8e0` chore, `788a38f` docs, `4988182` test — two concurrent-state interaction tests, `dc60e4f` chore).

**Re-run (Pass 1, 2026-06-04):** the prior Pass-1 returned `request-changes` with two blocking findings — the named interaction-scenario tests `concurrent_send_during_poll_no_torn_read` and `two_writers_no_overwrite` were absent at the sync seam. Both have since been added (commit `4988182`, `app/src/test/kotlin/org/openwebdav/messenger/sync/ConcurrentInteractionTest.kt`). Re-verified: both genuinely exercise the concurrent condition and assert the outcome; pipeline still green (test 124). Verdict updated to **approve**, DoD now **pass**. Blocking list cleared (recorded as resolved below). Non-blocking Notes retained.

Checked against `docs/features/sync_plan.md`, `.ai-pm/arch/sync_arch.md`, `docs/protocol/webdav-layout.md` (v2), `.ai-pm/state/current.md`, and the branch diff.

## Plan compliance — Scenarios (§ Scenarios, 8)

- ✓ **S1 send-once + per-member index** — `sync/SendWriter` (one `log/` PUT + (M−1) change entries; sender excluded). Test: `SendTest.send writes one log entry and one change entry per other member` + `send is idempotent on message id`.
- ✓ **S2 poll reads index → fetch only new → store** — `sync/PollReader.cycle`. Test: `PollCycleTest.poll fetches new entries and persists them`.
- ✓ **S3 full validation, reject/skip, never crash** — `PollReader.validateAndStore` (§3 hash via transport, §5.1 AEAD-open, §8 parse/verify). Tests: `PollInteractionTest.tampered file is skipped…`, `file under wrong key is rejected and skipped`, `SyncStackSpecTest.get rejects a body whose hash does not match the name`.
- ✓ **S4 dedup by id + order by token + reply-before-target** — Tests: `PollCycleTest.poll dedupes across cycles`, `poll orders by order token not arrival`, `reply to unreceived target is stored not rejected`.
- ⚠ **S5 offline catch-up within window** — catch-up half implemented and tested (`PollCycleTest.offline catch up fetches all newer than cursor`). The plan-named test's **second half** (cursor older than the window → only within-window messages available, documented loss) is **not** asserted. Untestable this feature because pruning is deferred (nothing trims `log/`, so no "older-than-window" state can be set up). See Note 1.
- ✓ **S6 unbounded local history, offline** — `data/MessageStore` + Room DAOs. Test: `MessageStoreTest.history is observable via the DAO flow`.
- ✓ **S7 chat key once per cycle (no per-message Argon2id)** — `PollReader.fetchAndPersist` loads `keyProvider.keyFor` once per chat. Test: `PollInteractionTest.chat key is loaded once per chat not per message` (injected counter asserts exactly 1 load for 3 messages).
- ✓ **S8 slow/rate-limited tolerance + Doze resume** — `429`/timeout → back-off, cursor not advanced; Doze resume from stored cursor. Tests: `PollInteractionTest.429 mid-fetch does not advance cursor past unfetched entries`, `cycle resumes from stored cursor after a long deferral`.

## Plan compliance — Interaction scenarios (§ Interaction scenarios)

The plan names 10 interaction-scenario tests (Test plan line 84). Mapping to the sync test suites:

- ✓ **`concurrent_send_during_poll_no_torn_read`** — `ConcurrentInteractionTest.concurrent send during poll sees a whole file or none and the next cycle catches up` (commit `4988182`). Sets up the in-flight intermediate state deterministically over `FakeDisk`: alice's §9.1 step 1 (`log/` PUT) has landed but step 2 (the change-entry notify to bob) has not. Bob's poll runs in that state via the §9.3 full-log fallback, reading the existing log file **whole** (`skippedCount == 0`, no `NotReady`/torn read) — exactly the append-only no-lock safety claim. The send then completes and bob's next cycle dedups via the durable cursor (`newCount == 0`, exactly one local row). Genuinely exercises the concurrent seam — not a happy-path poll — and asserts the outcome (whole-file-or-none + next-cycle catch-up). **Resolved.**
- ✓ **`two_writers_no_overwrite`** — `ConcurrentInteractionTest.two writers add distinct files to the shared log neither overwriting the other` (commit `4988182`). Two real `SyncEngine.send` calls (alice + carol, distinct senders/order-tokens → distinct §2 content-addressed names) into the **one shared** `ChatPaths.LOG` of a single chat root. Asserts two distinct files land in that shared `log/` (`disk.fileNames(ChatPaths.LOG).size == 2`, both names present), neither overwritten, and both decrypt for a reader (`newCount == 2`, ordered by order-token). Targets the **generation-2 shared `log/` at the sync seam** — not the v1 `inbox/` primitive the predating `transport/WebDavInteractionTest.concurrent_writers_distinct_names_no_overwrite` covered. **Resolved.**
- ✓ `duplicate_delivery_idempotent` — `PollCycleTest.poll dedupes across cycles`.
- ✓ `unresolved_reference_graceful` — `PollCycleTest.reply to unreceived target is stored not rejected`.
- ✓ `backoff_preserves_cursor` — `PollInteractionTest.429 mid-fetch does not advance cursor past unfetched entries` (asserts cursor stays at m1, resume re-fetches m2, no loss/dup).
- ✓ `incomplete_get_skipped_and_retried` — covered by the §3 NotReady path: `SyncStackSpecTest.get rejects a body whose hash does not match the name` + `PollInteractionTest.tampered file is skipped…` (both drive the `ReadResult.NotReady → Skipped, retried next cycle` branch). Acceptable.
- ✓ `tampered_file_skipped` — `PollInteractionTest.tampered file is skipped and the cycle continues for the rest` + `file under wrong key is rejected and skipped`.
- ✓ `doze_deferred_cycle_resumes_from_cursor` — `PollInteractionTest.cycle resumes from stored cursor after a long deferral`.
- ⚠ `long_offline_catch_up_window_bounded` — catch-up half tested; window-bounded (older-than-window) half not (see S5 / Note 1).
- ✓ `key_derived_once_per_cycle` — `PollInteractionTest.chat key is loaded once per chat not per message`.

## Plan compliance — Stack-spec tests (§ Test plan / Stack expectations)

Each asserts against the cited rule (source URL present), not a self-consistent round-trip:

- ✓ **15-min WorkManager floor** — `SyncStackSpecTest.periodic poll request is at least the 15 minute floor` asserts `intervalDuration >= PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` even when 1 min is requested. Source cited.
- ✓ **WorkManager TestDriver** — `worker runs a cycle via the work-testing TestDriver`. Source cited.
- ✓ **Room no-main-thread** — `MessageStoreTest.database is built without allowMainThreadQueries` reflects the configured flag is off; DAO surface is suspend/Flow/PagingSource. Source cited.
- ✓ **Room migration / schema export** — `exportSchema = true`, `app/schemas/org.openwebdav.messenger.data.MessengerDatabase/1.json` checked in; `androidTest/MessengerDatabaseMigrationTest` (MigrationTestHelper create + real-DB open). Device-gated (see pipeline). Honest for a v1 schema (no prior version to migrate from yet; harness in place for v1→v2).
- ✓ **PROPFIND Depth:1** — `poll PROPFIND sets Depth 1 never infinity` asserts on the actual header MockWebServer received. Source cited.
- ✓ **§3 hash-mismatch reject** — `get rejects a body whose hash does not match the name`. Source cited.
- ✓ **chat-key-derived-once-per-cycle** — `chat key is loaded once per chat not per message` (injected counter). Source cited.

## Plan compliance — Contracts (§ Contracts behavioral shapes)

- ✓ **Idempotent send, never overwrites, partial-failure tolerant** — `SendWriter` (content-addressed log + cursor-addressed entries, non-conditional PUTs); `SendTest.send tolerates partial change-index failure and is resumable`.
- ✓ **Cursor advances only past fetched-and-persisted** — `PollReader.fetchAndPersist` advances per persisted entry; back-off returns before unfetched entries. Tested (backoff_preserves_cursor).
- ✓ **Typed results, never throw** — `CycleOutcome` / `SendOutcome`; reject/back-off mapped to typed values, no throw path on the cycle.
- ✓ **Off-main-thread Room** — suspend/Flow/PagingSource DAOs, no `allowMainThreadQueries`.
- ✓ **Roster as input** — `SyncEngine.send` takes the member set as a parameter; sync does not manage membership (§1.3).

## Plan compliance — Docs to update

- ✓ **webdav-layout v2** — present (protocol version 2, §1 shared-log + change-index, §9 cursor semantics, §1.4 window reserved). Authored pre-coding, consistent with the implementation.
- ✓ **architecture.md** — decision #3 revised (shared-log + change-index + window); Behavioral-contract change-index-as-optimization invariant; module map `data/` + `sync/` flipped to Implemented with observed-file lists matching the tree.
- ✓ **threat-model.md** — T16 (Room at-rest history), T17 (retention-window operator exposure), T18 (change-index metadata), T19 (cooperative honest-client polling); SC17 added in architecture Security constraints; coverage matrix row for `sync`; `Last reviewed: 2026-06-04`. REQUIRED security-touching update present and consistent. **Not blocking.**
- ✓ **user-journeys.md** — first real journey authored (send / background receive / offline catch-up / window limit), human-language, with the honest window-loss drop-off.

## Security-touching update on a security-bearing project

`docs/threat-model.md` is present (security-bearing). Feature touches data-at-rest (Room history) and network/transport — `### Security-relevant surfaces`. Plan lists the threat-model update in Docs to update, and it landed (T16–T19/SC17). Requirement satisfied — not blocking.

## Definition of Done

- [x] All plan scenarios implemented and tested — *except S5's window-bounded half (Note 1, untestable until pruning lands)*
- [x] Interaction scenarios have concurrent-state tests — both named tests now present and exercise the concurrent condition (`ConcurrentInteractionTest`, commit `4988182`); prior Blocking #1/#2 resolved
- [x] Stack expectations respected; stack-spec tests pass (JVM gates green; Room-migration stack-spec is device-gated, honestly blocked)
- [x] Product Contract honored — N/A: backend substrate, no UI; "skip" is honest and matches the prior four substrates (state file + module map confirm no user-facing surface). Not flagged as a gap.
- [x] Pipeline green — `./gradlew test` (124) + `ktlintCheck` + `lint` all BUILD SUCCESSFUL (re-verified this re-run); `connectedAndroidTest` honestly BLOCKED (no device) with Room-migration + native-crypto gated — consistent with prior substrates, not a false green
- [x] State file updated (`.ai-pm/state/current.md`, coding-done with accurate file/test inventory; updated `dc60e4f`)
- [x] Product Impact Report — N/A (no contract touched); coder recorded the no-contract reason
- [x] Docs updates landed (webdav-layout v2, architecture, threat-model, user-journeys)
- [x] Expected artifacts exist — plan, this review; no contract required (backend substrate)

**DoD: pass.**

## Blocking

(none — both prior Pass-1 blocking findings resolved)

Resolved this re-run:

1. ~~Missing interaction-scenario test `concurrent_send_during_poll_no_torn_read`~~ — **resolved** by `ConcurrentInteractionTest.concurrent send during poll sees a whole file or none and the next cycle catches up` (commit `4988182`, `app/src/test/kotlin/org/openwebdav/messenger/sync/ConcurrentInteractionTest.kt`). Stages the in-flight state (log/ PUT done, change-entry notify pending), polls in that state asserting whole-file-or-none (`skippedCount == 0`, `newCount == 1`), then completes the send and asserts next-cycle cursor+id dedup. Exercises the concurrent seam, not a happy path.
2. ~~Missing interaction-scenario test `two_writers_no_overwrite`~~ — **resolved** by `ConcurrentInteractionTest.two writers add distinct files to the shared log neither overwriting the other` (commit `4988182`, same file). Two real `SyncEngine.send` calls into one shared `ChatPaths.LOG`; asserts two distinct §2 files land, neither overwritten, both decrypt — at the generation-2 sync seam, not the v1 inbox primitive.

## Notes (product)

1. **Offline-catch-up "older-than-window unavailable" half is not exercised** (scenario 5 / `offline_catch_up_within_window` second half / `long_offline_catch_up_window_bounded`). This is a direct consequence of the PM's scope decision to defer retention-window pruning: nothing trims `log/`, so the "messages older than the window are gone from the disk" state cannot be set up or asserted in this feature — it only becomes testable when the pruning follow-on lands. The catch-up (within-window) half is fully tested. Why it matters: the honest "you lose messages older than the window" limit (surfaced in the user journey and threat model) is asserted only in prose/docs right now, not in a test; the follow-on pruning feature must carry the window-bounded-loss test. No action needed this feature beyond awareness that the limit is currently doc-asserted, not test-asserted.

2. **Test-only Robolectric 4.13 harness not in `docs/stack-notes.md`.** The coder added Robolectric 4.13 (pinned to SDK 34 via `robolectric.properties`, since 4.13 tops out at 34 while `targetSdk=35`) as the JVM harness for the Room-in-memory + WorkManager `TestDriver` tests under `./gradlew test`. It is a test-only harness, not a product dependency, and does not change runtime behavior — acceptable as-is and not blocking. Why it matters: the SDK-34 pin is a real constraint a future contributor will trip over (a Robolectric test using an SDK-35-only API would fail opaquely). Recommend a short `docs/stack-notes.md` entry recording the harness + the SDK-34 pin rationale (the state file already flags surfacing it to pm-stack-researcher). PM's call whether to do it now or note it.

## Verdict

approve

<!-- orchestrator appends after code-review pass: -->
## Code review findings

Pass 2 — `code-review` high effort, 2026-06-04 (round 1). 7 finder angles → verified by direct code-read. The advanceCursor concurrency race candidates were REFUTED (`SyncScheduler.enqueueUniquePeriodicWork` → cycles never overlap). Findings to fix, severity order:

1. **[correctness — silent message loss] `PollReader.fetchAndPersist` (PollReader.kt:146 + MessageStore.advanceCursor:46).** The cursor advances past a transient not-ready (incomplete-upload) entry when a later entry in the same batch persists. `pending=[msg@002 (another sender's upload still computing its hash → transport NotReady), msg@003 (complete)]`: 002 is Skipped (cursor left), 003 persists → `advanceCursor(003)`; next cycle `selectPending` filters `orderToken>003`, so 002 (now complete) is never re-fetched → lost. This is exactly the slow-Yandex incomplete-write case §3 says to retry. Fix: advance the cursor only over the longest **contiguous** persisted prefix — stop at the first NotReady gap. Must distinguish transient **NotReady** (block the cursor, retry) from permanent **Rejected** (forged/tampered — must NOT wedge the cursor forever under flat trust); today both collapse to `FetchStep.Skipped`.

2. **[efficiency — round-trip storm] `PollReader.drainChat` (PollReader.kt:99).** `cycle()` loops K subscriptions; each `drainChat` calls `transport.list(ChatPaths.LOG)` on the same shared chat-root log → K identical full-log PROPFINDs per cycle — the storm the rework exists to kill. List the shared log once per cycle and reuse. This also surfaces an under-specified chat-root↔chat cardinality: `selectPending` never scopes by chat-id, relying on AEAD key-mismatch to discard other chats' entries (re-GETing + failing them every cycle). Clarify/scope; at minimum list-once.

3. **[efficiency] `SendWriter.notifyMember` (SendWriter.kt:68).** `ensure(ChatPaths.CHANGES)` (an idempotent MKCOL round-trip) runs once per member inside the loop. Hoist it above the member loop — M−1 round-trips saved per send.

4. **[altitude — latent trap for the compression feature] `PollReader.validateAndStore` (PollReader.kt:186).** Header reconstruction hard-codes `codec-id=0x00`. Correct for THIS feature (codec always 0x00), but when the compression feature sets `codec-id=0x01` the rebuilt AAD will not match what the sender bound → AEAD-open fails → `Rejected` → the message is silently dropped, with no "unsupported codec" signal. The transport should surface the real header bytes (or PollReader should read codec-id and reject-with-reason on an unknown codec) rather than assume 0x00. Fix now (cheap guard) so the next feature doesn't inherit a silent-loss path.

5. **[robustness] `PollReader.selectPending` (PollReader.kt:118).** The order-token used in the `> cursor` comparison comes from `MessageId.splitMessageId`, which validates the content-hash length but not the order-token length/alphabet (§4). AEAD gates the actual cursor advance (a non-member can't poison it), but the listing-stage comparison still trusts an unvalidated substring. Validate full §2 message-id well-formedness (`MessageId.isWellFormedMessageId`) at selection.

6. **[reuse] `ChangeEntry.chatTag` (ChangeEntry.kt:36).** Re-implements the `b32lower(SHA-256(x))[0:N]` tag pattern with its own `MessageDigest.getInstance`, duplicating `OrderToken.senderTag` and `MessageId`; `ORDER_TOKEN_CHARS` is re-declared here and in `MessageId`. Extract a shared `protocol` hash-tag helper + a single order-token alphabet constant so a future change is made once, not in three places.

7. **[reuse — minor] `MessageStore.toHex` (MessageStore.kt:99).** Hand-rolled hex encoder; extract a `protocol/Hex` helper (none exists yet) so the next layer needing hex reuses it.

(Non-blocking awareness, no fix this feature: the v1 `inbox/` helpers in `ChatPaths` are retained as live API for the predating transport tests — acceptable, could be `@Deprecated`.)

### Round 2 — fixes verified (2026-06-04)

All 7 fixed in commits `861366d` (fix #1/#2/#4/#5), `fea8c40` (#3), `a519720` (#6/#7); 124→127 tests, all prior tests green unchanged (proves #6/#7 byte-identical). Verified by direct code-read:

- **#1** — new `sync/CursorPrefix.kt` + `FetchStep` split (`NotReady` vs `Rejected`): the cursor advances only over the longest **contiguous resolved** prefix and freezes at the first NotReady-only coordinate gap (retried, never skipped past); a permanent Rejected (forged/tampered/unsupported-codec) resolves its coordinate so a planted file cannot wedge the chat. Backoff commits the resolved prefix so far. New tests: `CursorContiguousPrefixTest` (loss-scenario fixed, forged-file-not-wedging, unsupported-codec-rejected).
- **#2** — `cycle()` lists `log/` once and reuses it across drains, keeping the steady-state no-op (lists only when ≥1 chat needs a drain). Chat-root↔chat cardinality documented in-code.
- **#3** — `SendWriter` ensures `changes/` once per send (hoisted above the member loop).
- **#4** — transport exposes the real on-disk `codecId`; `validateAndStore` rebuilds the AAD faithfully and reject-with-reasons a non-`0x00` codec instead of a silent AAD-mismatch drop.
- **#5** — `selectPending` validates full §2 well-formedness (`MessageId.isWellFormedMessageId`) before a name's order-token enters the cursor comparison.
- **#6/#7** — `protocol/HashTag` + `protocol/Hex` extracted; `ChangeEntry`/`OrderToken`/`MessageId`/`MessageStore` consolidated onto them.

Spec refinement surfaced by #1 (non-blocking, handed to `pm-architect`): `webdav-layout.md` §3 lumps all hash-mismatch into one "not-ready/retry" bucket; the no-loss invariant needs the finer NotReady-at-a-fresh-coordinate (block + retry) vs resolved-coordinate (advance past) distinction stated. One sentence added to §3.

## Code review: 2026-06-04 — passed

Pass 2 clean after round 2: all 7 findings resolved and re-verified by code-read; no regressions introduced by the fixes; pipeline green (`test` 127 / `ktlintCheck` / `lint`), `connectedAndroidTest` device-blocked (unchanged). Both review passes (plan-compliance + code-review) clear.
