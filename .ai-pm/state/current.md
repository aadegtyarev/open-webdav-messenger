# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

sync — turn the four shipped substrates into a working conversation: new on-disk layout (shared per-chat log + per-user change index + reserved retention window), send (fan-out via index, one log copy), receive (background WorkManager poll → validate → dedup → Room), offline catch-up within the window, in-memory chat-key caching. Backend substrate, no UI.

## Status

review-clean — both passes cleared (awaiting PM ship decision)

## Done

- Audit-2026-06-04 closed; sync planning (plan + arch note Variant A + reworked webdav-layout v2) committed as baseline.
- pm-coder implementation (feature/sync), pipeline-green on the three JVM gates:
  - **protocol/**: `ChatPaths` reworked to generation-2 (`log/` + `changes/<member-index-id>/`); v1 `inbox/` helpers retained as pure string fixtures for the predating transport tests (layout superseded, not the string minting). New `ChangeEntry` (§9.2 `chat-tag~order-token` name; reader takes MAX order-token per chat-tag, parse ignores malformed/foreign entries).
  - **data/** (new): Room `MessageEntity` (PK = §2 message-id → idempotent dedup), `SyncCursorEntity` (per-chat order-token high-water), suspend/Flow/PagingSource DAOs, `MessengerDatabase` (exportSchema=true → app/schemas/1.json committed, no allowMainThreadQueries, no destructive fallback), `MessageStore` persistence seam (forward-only cursor advance).
  - **sync/** (new): `SyncEngine` (send §9.1 + pollCycle §9.3) over `SendWriter` + `PollReader`; cursor advances ONLY past persisted entries (429/Doze no-skip invariant); ChatKey loaded ONCE per chat via `ChatKeyProvider` (no per-message Argon2id). `CycleOutcome`/`SendOutcome` typed results (never throw). `SyncWorker` (CoroutineWorker, one run = one cycle, backedOff→retry) + `SyncScheduler` (PeriodicWorkRequest clamped to 15-min floor) + `SyncRunner` install seam.
  - **build**: Room/WorkManager/Paging deps, KAPT (top-level room.schemaLocation), Robolectric (JVM harness for Room in-memory + work-testing TestDriver), schema delivered to androidTest assets.
  - **tests** (23 new JVM, all green; 99 existing untouched + green): SendTest(3), PollCycleTest(7), PollInteractionTest(5), SyncStackSpecTest(4), MessageStoreTest(4) + androidTest MessengerDatabaseMigrationTest (2, device-gated). FakeDisk in-memory WebDAV fixture + SyncTestSupport (real libsodium).
- pm-plan-checker pass-1 (`.ai-pm/reviews/sync_review.md`) Blocking #1 + #2 FIXED: added the two missing concurrent-state interaction-scenario tests at the sync seam over the generation-2 shared `log/` — `ConcurrentInteractionTest` (2 new JVM tests). Test count 122 → 124. Pass-1 verdict: approve.
- pm-coder pass-2 (code-review high effort, 7 findings) FIXED on `feature/sync`:
  - **#1 (correctness, silent message loss):** the §9.3 cursor now advances only over the longest contiguous RESOLVED prefix of the log/ listing, grouped by order-token coordinate (`sync/CursorPrefix`). Transient NotReady (incomplete upload / §3 hash-mismatch) at a fresh coordinate freezes the cursor (retry next cycle, no skip-past); permanent Rejected (AEAD/sig fail / unsupported codec — a forged file under flat trust) does NOT wedge — advanced past. `FetchStep` split into NotReady vs Rejected.
  - **#4 (altitude):** the reader rebuilds the §5.1 AEAD AAD from the REAL on-disk codec-id (`ReadResult.Ready` carries it; `Envelope.frame(codecId, blob)`) and reject-with-reasons a non-0x00 codec explicitly — no silent compression-era loss.
  - **#2 (efficiency):** `log/` listed ONCE per cycle, reused across subscriptions' drains; steady-state no-op still pays zero log PROPFINDs (`needsDrain`).
  - **#3 (efficiency):** `ensure(changes/)` hoisted above the member loop in `SendWriter` (M-1 MKCOLs saved/send).
  - **#5 (robustness):** `selectPending` validates full §2 well-formedness (`MessageId.isWellFormedMessageId`) before the order-token enters the cursor comparison.
  - **#6 (reuse):** `protocol/HashTag` consolidates the `b32lower(SHA-256(x))[0:N]` pattern + order-token alphabet (used by `MessageId`/`OrderToken`/`ChangeEntry`).
  - **#7 (reuse):** `protocol/Hex` extracted from `MessageStore.toHex`.
  - 3 new JVM tests (`CursorContiguousPrefixTest`): the loss scenario fixed, forged-low-not-wedging, unsupported-codec-rejected. Existing 124 unchanged + green → 127 total. #6/#7 byte-identical (existing tests prove it).
- Pipeline: `./gradlew test` (127 tests) + `ktlintCheck` + `lint` all GREEN. `connectedAndroidTest` BLOCKED — no device available (Room migration + native-crypto gate pending a device, same as prior substrates; all new tests are JVM, not device-gated).

## Remaining

- code-review pass 2 re-verified CLEAN (orchestrator code-read): all 7 fixed, no regressions; `sync_review.md` stamped "Code review: 2026-06-04 — passed". §3 cursor-interaction clarification added by pm-architect. product-map regenerated (sync → Infrastructure). Committed (review trail ee0c4b0).
- AWAITING PM: ship decision (test-first / PR-then-merge / ship-now) → then pm-pr-prep; after merge archive state + reset to idle.
- Surfaced to PM as a product note: SC17/T16 — local Room history is NOT app-encrypted at rest (relies on device-lock / OS storage encryption); accept as MVP limit (recorded) or backlog full-DB encryption.
- App-startup wiring (SyncRunner.install + SyncScheduler.schedule) is intentionally deferred to the future config/UI feature that supplies connection config + roster; until then SyncWorker runs the default no-op runner. Recorded in OpenWebDavMessengerApp doc.

## Next step

Re-run `code-review` (pass 2) to confirm clean; on clean → product-map regen + state archive.

## Touched files (this fix — pass-2 code-review)

- `app/src/main/kotlin/org/openwebdav/messenger/protocol/HashTag.kt` (NEW — #6 shared hash-tag helper + order-token alphabet).
- `app/src/main/kotlin/org/openwebdav/messenger/protocol/Hex.kt` (NEW — #7 hex helper).
- `app/src/main/kotlin/org/openwebdav/messenger/sync/CursorPrefix.kt` (NEW — #1 FetchStep + CursorPrefix contiguous-prefix bookkeeping).
- `app/src/main/kotlin/org/openwebdav/messenger/sync/PollReader.kt` (#1 cursor contiguous-prefix + NotReady/Rejected; #2 list-once + needsDrain; #4 codec guard; #5 well-formedness).
- `app/src/main/kotlin/org/openwebdav/messenger/protocol/Envelope.kt` (#4 `frame(codecId, blob)` + `Frame.codecId`).
- `app/src/main/kotlin/org/openwebdav/messenger/transport/WebDavTransport.kt` (#4 `ReadResult.Ready.codecId` from the real on-disk header).
- `app/src/main/kotlin/org/openwebdav/messenger/sync/SendWriter.kt` (#3 hoist `ensure(changes/)`).
- `app/src/main/kotlin/org/openwebdav/messenger/protocol/MessageId.kt`, `OrderToken.kt`, `ChangeEntry.kt` (#6 use HashTag — byte-identical).
- `app/src/main/kotlin/org/openwebdav/messenger/data/MessageStore.kt` (#7 use Hex — byte-identical).
- `app/src/test/kotlin/org/openwebdav/messenger/sync/CursorContiguousPrefixTest.kt` (NEW — 3 tests for #1 + #4).

## Validation

Pipeline: `./gradlew test` (JVM: MockWebServer + Robolectric Room in-memory + work-testing TestDriver), `./gradlew ktlintCheck`, `./gradlew lint` — all GREEN. `./gradlew connectedAndroidTest` (Room migration + native crypto) — BLOCKED, no device; APKs proven to compile/merge via assembleDebug + assembleDebugAndroidTest.

## Notes

Branch: feature/sync (cut from main a24d7f3). Today 2026-06-04. No git remote — local squash-merges.
Backend substrate (no UI) → NO Product Contract (matches prior four substrates; noted in coder report). Security-bearing + touches data-at-rest/storage/transport → threat-model.md update REQUIRED (in plan Docs to update, done post-coding by pm-architect).
New test-only dep: Robolectric 4.13 (JVM harness for Room/WorkManager under `./gradlew test`; pinned SDK 34 via robolectric.properties since 4.13 tops out at 34 while app targetSdk=35). Not a product dependency. Surface to pm-stack-researcher to add a Robolectric note to stack-notes if reviewer wants it cited.
Downstream expectation honored: ChatKey loaded once per chat per cycle (ChatKeyProvider), no per-message Argon2id (crypto review 2026-06-03) — asserted by chat_key_not_rederived_per_message via an injected counter.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` and reset this one to a new task or to "Status: idle".
