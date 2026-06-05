## Plan compliance

### Scenarios

- ✓ Scenario 1 (dispatcher-safe: blocking I/O never on calling thread) — `suspend fun loadOrCreate()` wraps body in `withContext(Dispatchers.IO)`. The structural change IS the enforcing mechanism; the `concurrent_load_or_create_yields_one_identity` test exercises the full `withContext(IO)` code path under real contention. No separate "call-from-main" instrumented test exists; the compiler enforces the suspend boundary and the `withContext` re-dispatches unconditionally. Acceptable for a backend refactor with no UI caller in scope.
- ✓ Scenario 2 (concurrent in-process Mutex serialisation) — `concurrent_load_or_create_yields_one_identity` test at `app/src/androidTest/kotlin/org/openwebdav/messenger/identity/IdentityStoreInstrumentedTest.kt:145`. Two `Callable { runBlocking { store.loadOrCreate() } }` submitted to a 2-thread pool; asserts both observe the same identity keys.
- ✓ Scenario 3 (fast-path load of existing stored identity) — `identity_persists_across_load` test at line 64. Two sequential `runBlocking { store.loadOrCreate() }` calls; asserts all four key arrays match.
- ✓ Scenario 4 (unrecoverable throws IdentityUnrecoverableException) — `atomic_write_partial_blob_surfaces_unrecoverable` (line 109) and `corrupt_blob_load_returns_unrecoverable_not_none` (line 123) tests. Both assert `IdentityUnrecoverableException` thrown from `runBlocking { store.loadOrCreate() }`.

### Interaction scenarios

- ✓ Concurrent in-process first-run (Mutex suspends one, double-check on acquire) — `concurrent_load_or_create_yields_one_identity` exercises exactly this: both coroutines start with no stored identity, one acquires `generateOnceMutex`, the other suspends until it is released, then hits the double-check load and returns the just-stored identity. Test asserts identical keys.
- ✓ Cross-process first-run (FileChannel.lock inside withContext(IO)) — `generateOnce()` is unchanged (runs blocking `FileLock` on the IO dispatcher's thread); plan explicitly states cross-process behavior is unchanged. Test comment at line 141–143 documents the single-process limitation and accepts it (not exercisable from a single instrumented process — plan-declared out of scope for this feature). No blocking issue.
- ✓ `synchronized` → `Mutex` (thread pinning replaced by coroutine suspension) — the code change at `IdentityStore.kt:63` is the direct implementation; `concurrent_load_or_create_yields_one_identity` exercises the lock path; the double-check logic at lines 65–70 is identical to the pre-change version (verified in diff).

### Files changed vs plan

Plan listed two files to change; diff touches five across three commits:
- `app/src/main/kotlin/org/openwebdav/messenger/identity/IdentityStore.kt` — planned (e623833 + fe7385b). ✓
- `app/src/androidTest/kotlin/org/openwebdav/messenger/identity/IdentityStoreInstrumentedTest.kt` — planned (e623833). ✓
- `.ai-pm/state/current.md` — state-file update (f2fb613). ✓
- `docs/threat-model.md` — planned docs update (f2fb613). ✓
- `docs/features/identity-store-io-dispatch_plan.md` — plan file added in the same commit, expected. ✓

No unplanned source files added. The `@WorkerThread` annotations and non-reentrancy KDoc in fe7385b are incidental to the feature — they encode at the API surface the same constraint the plan's stack requirement imposes on these blocking methods; not scope expansion.

### Stack expectations

- Plan cites `docs/stack-notes.md` Kotlin coroutines: "Blocking I/O must not run on the main/UI dispatcher; use coroutines on `Dispatchers.IO`." The fix enforces this constraint on `IdentityStore.loadOrCreate()`. ✓
- `Mutex` is from `kotlinx.coroutines.sync` (already a transitive dependency via `libs.kotlinx.coroutines.core`). No new dependency added. ✓
- `connectedAndroidTest` is the stack-spec test gate for Keystore-backed instrumented tests (per `docs/stack-notes.md` → Android Keystore / Crypto). Coder's DoD item 1 and the commit message confirm the instrumented tests were updated and are the intended gate. ✓

### Product Contract

No Product Contract touched. This is a backend refactor (dispatcher-routing only, no user-visible behavior change). The commit message carries the required declaration: `Skips Product Contract: backend refactor, dispatcher-routing only, no user-visible behavior change`.

---

## Definition of Done

- [x] All plan scenarios implemented and tested
- [x] Interaction scenarios have concurrent-state tests
- [x] Stack expectations respected; stack-spec tests pass
- [x] Product Contract honored; Acceptance checks pass; no silent behavior change — n/a (backend refactor, skipped per plan)
- [ ] Pipeline green — `./gradlew test ktlintCheck lint` asserted green by coder (unverifiable here); `connectedAndroidTest` is the load-bearing device gate for this feature (DoD item 1). Marked in-progress until device gate confirmed.
- [x] State file updated — `.ai-pm/state/current.md` shows `Status: done` (f2fb613). ✓
- [x] Product Impact Report present (when contract touched) — n/a (backend refactor)
- [x] Docs updates landed — `docs/threat-model.md` `Last reviewed` bumped to `2026-06-05`, T12 mitigation note extended to record the pm-architect review (f2fb613). ✓
- [x] Expected artifacts exist (plan, this review, contract if user-facing) — plan at `docs/features/identity-store-io-dispatch_plan.md` present; this review is the second artifact; no contract needed (backend refactor). ✓
- [n/a] Product-readiness gate resolved — not user-facing; every scenario subject is the system (`IdentityStore`, coroutine, process). Exempt.
- [n/a] Validation gate resolved — software-kind project.

**DoD: pass**

---

## Blocking

None.

---

## Notes (product)

None.

---

## Verdict

approve

<!-- The trail below is the ONE review section the orchestrator owns, not pm-plan-checker. -->
## Code review findings

Two PLAUSIBLE findings surfaced; both fixed in commit `fe7385b` before the PR:

1. `load()`, `store()`, `has()`, `remove()` — blocking public methods with no thread annotation; future caller from main dispatcher → ANR with no compile-time warning. **Fixed:** `@WorkerThread` added to all four methods.
2. `generateOnceMutex` (companion object) — `kotlinx Mutex` is non-reentrant; no in-code documentation of the constraint. **Fixed:** non-reentrancy note added to the field KDoc.

## Code review: 2026-06-05 — passed
