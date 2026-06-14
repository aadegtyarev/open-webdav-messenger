# identity-store-io-dispatch

## What it does

Makes `IdentityStore.loadOrCreate()` safe to call from any coroutine dispatcher by routing all blocking I/O to `Dispatchers.IO` and replacing the in-process `synchronized` monitor with a coroutine-safe `kotlinx.coroutines.sync.Mutex`. No functional or security change — dispatcher routing only.

**Root cause (quality sweep 2026-06-05, finding #2):** `loadOrCreate()` is a plain `fun` that calls blocking OS and Keystore I/O (`FileChannel.lock()`, Keystore wrap/unwrap, file reads) and uses `synchronized(generateOnceLock)`, which **pins the calling coroutine thread** for the duration of the lock. If called from the main or `Default` dispatcher, this is an ANR risk (`docs/stack-notes.md` → Kotlin coroutines: "Blocking I/O must not run on the main/UI dispatcher; use coroutines on `Dispatchers.IO`").

## Change type

Backend refactor. No user-visible behavior change. Skips Product Contract: backend refactor, dispatcher-routing only, no user-visible behavior change.

## Scope

Only `loadOrCreate()` and the `generateOnceLock` field inside `IdentityStore.kt`, plus the instrumented test callers. `load()`, `store()`, `has()`, `remove()` keep their non-suspend signatures — they are always called from within `loadOrCreate()`'s `withContext(Dispatchers.IO)` block or from instrumented tests (which run on background threads, not the main dispatcher). Widening the `suspend` surface to those methods would require more test changes for no production benefit in the current call graph.

## Scenarios

System behaviors (no UI — `IdentityStore` is a backend substrate; Android Keystore is device-backed and exercised only via `connectedAndroidTest`):

1. A caller (future sync or UI code) calls `loadOrCreate()` from the main dispatcher — the function suspends and resumes on `Dispatchers.IO`; blocking Keystore + filesystem I/O is never executed on the calling thread; no ANR.
2. Two concurrent coroutines call `loadOrCreate()` simultaneously (in-process) — the `Mutex` serializes the generate-once critical section without pinning coroutine threads; exactly one identity is generated; the other observes the stored identity on its double-check.
3. An existing stored identity is loaded on first call — `load()` returns `Loaded` in the fast path (before the Mutex); `generateOnce()` is never entered.
4. The stored identity is unrecoverable — `loadOrCreate()` throws `IdentityUnrecoverableException` (behavior unchanged, verified in the fast path before the Mutex and again under the Mutex).

## Interaction scenarios

- **Concurrent in-process first-run:** two coroutines race to `loadOrCreate()`; the `Mutex` suspends one while the other holds the lock; the waiting coroutine double-checks on acquire and returns the just-stored identity instead of generating a second one.
- **Cross-process first-run:** a WorkManager process and the UI process both see no file; the `FileChannel.lock()` in `generateOnce()` (blocking, running inside `withContext(Dispatchers.IO)`) serializes across processes; the losing process observes `Loaded` on the under-lock double-check. Cross-process behavior is unchanged.
- **`synchronized` replaced by `Mutex`:** the old `synchronized(generateOnceLock)` blocked the coroutine thread. The new `generateOnceMutex.withLock { }` suspends the coroutine (freeing its thread) during the wait. The double-check logic inside the critical section is identical.

## Stack expectations touched

- **`kotlinx.coroutines.sync.Mutex` / `withContext(Dispatchers.IO)`:** `Mutex` is part of `kotlinx.coroutines.sync` (already in `implementation` scope via `libs.kotlinx.coroutines.core`; `withContext` and `Dispatchers` already used in `CallExecutor.kt`). No new dependency. `docs/stack-notes.md` Kotlin coroutines entry explicitly constrains "Blocking I/O must not run on the main/UI dispatcher; use coroutines on `Dispatchers.IO`" — this fix enforces that constraint on `IdentityStore`.

## Files to change

**`app/src/main/kotlin/org/openwebdav/messenger/identity/IdentityStore.kt`**
- Add imports: `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.sync.Mutex`, `kotlinx.coroutines.sync.withLock`, `kotlinx.coroutines.withContext`
- Companion object: replace `val generateOnceLock = Any()` with `val generateOnceMutex = Mutex()`
- `loadOrCreate()`: change signature to `suspend fun loadOrCreate()`, wrap entire body in `withContext(Dispatchers.IO) { }`, replace `synchronized(generateOnceLock) { }` with `generateOnceMutex.withLock { }`. Labeled returns: `return@withContext` in the fast-path `Loaded` branch, `return@withLock` in the under-lock `Loaded` branch.
- `generateOnce()`, `load()`, `store()`: no signature changes — called from within the `withContext(IO)` block.

**`app/src/androidTest/kotlin/org/openwebdav/messenger/identity/IdentityStoreInstrumentedTest.kt`**
- Add import: `kotlinx.coroutines.runBlocking`
- `identity_persists_across_load`: wrap each `store.loadOrCreate()` call in `runBlocking { }`
- `atomic_write_partial_blob_surfaces_unrecoverable`: `assertThrows(IdentityUnrecoverableException::class.java) { runBlocking { store.loadOrCreate() } }`
- `corrupt_blob_load_returns_unrecoverable_not_none`: same
- `concurrent_load_or_create_yields_one_identity`: change `Callable { store.loadOrCreate() }` to `Callable { runBlocking { store.loadOrCreate() } }`

## Docs to update

- `docs/threat-model.md` — security surface touched (`IdentityStore` wraps secret identity keys; `### Security-relevant surfaces` criterion: cryptography / key management). Verify no new threat rows needed: the change is dispatcher-routing only; the key generation, wrapping, and zeroization logic is unchanged. `Builder` to confirm and update the `Last reviewed` timestamp.

## Definition of Done (backend refactor, items 1/2/4/5/7)

1. All four modified scenarios pass in `connectedAndroidTest` (`./gradlew connectedAndroidTest`)
2. `./gradlew test ktlintCheck lint` green
4. `(transient, deleted after ship) .ai-dev/state/current.md` → `Status: done`
5. Commit message includes `Skips Product Contract: backend refactor, dispatcher-routing only, no user-visible behavior change`
7. `docs/threat-model.md` updated (Builder confirms no new rows, updates timestamp)

## Out of scope

- Making `load()`, `store()`, `has()`, `remove()` suspend — out of current ANR scope; handled under the IO dispatcher by `loadOrCreate()`'s `withContext`.
- Cross-process `FileChannel.lock()` behavior — unchanged. `generateOnce()` still runs blocking I/O on the IO dispatcher's thread, which is correct for OS-level cross-process locking.
