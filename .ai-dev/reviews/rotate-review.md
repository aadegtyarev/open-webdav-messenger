## Code review: CHANGES REQUESTED

Runtime verification: suite — `./gradlew ktlintCheck lint test` ran and passed (BUILD SUCCESSFUL, 82 tasks: 1 executed, 81 up-to-date); `CredentialRotationTest` target-specific run also passed.

---

### Findings

#### F1 · BLOCKING · Rotation broken for non-host members — wrong Ed25519 verification key

**File:** `app/src/main/kotlin/org/openwebdav/messenger/app/EngineWiring.kt:188`

```kotlin
val hostSignPub = g.identity.copySignPublic()
```

`g.identity` is the **local** user's identity, not the host's. For a non-host member, `g.identity.copySignPublic()` returns their own signing public key — not the host's. The host signed the rotation blob with the host's secret key, so `IdentityCrypto.verify(signature, payload, hostSignPub)` **always fails** for non-host members. `CredentialRotation.openForMember()` returns `null`, and the rotation is silently dropped.

- **Consequence:** The feature's core claim — "remaining members auto-detect and apply the new credential on next poll" — is false for every non-host member. Credential rotation never reaches them.
- **Root cause:** The plan says *"verifier uses the signer public key embedded in the payload"* (listed as "out of scope: Host public key resolution from directory") — but `sealForMember()` at `CredentialRotation.kt:42–54` does **not** embed the host's public key in the sealed blob. The blob contains only `JSON ‖ Ed25519-signature`. The caller MUST supply the host's key, and the caller supplies the local key.
- **Security implication:** The feature's SC12 claim ("rotation payloads MUST be Ed25519-signed … verification fails unless attacker has host's signing secret") overstates what the code actually enforces. For a non-host member, the verification key is the member's own key — a planted blob signed with the member's key would pass, though that's moot since legitimate rotation blobs fail too.
- **Fix direction:** Either (a) embed the host's Ed25519 public key in the sealed payload in `sealForMember()` and extract it in `openForMember()`, or (b) add `hostSignPublic` parameter to the poll-path call and resolve it from the directory/roster. The plan already named option (a) — implement it.

#### F2 · BLOCKING · `saveRotatedConfig` persists config under wrong community ID

**File:** `app/src/main/kotlin/org/openwebdav/messenger/app/EngineWiring.kt:340`

```kotlin
configStore.save(newConfig, stored.chatId, stored.communityName, communityId = stored.communityName)
```

`stored.communityName` is a human-readable name (e.g. `"My Chat"`), not a machine-readable community ID (e.g. `"abc123..."` or `"default"`). `ConnectionConfigStore.save()` uses `communityId` to derive the Keystore alias and the backing file name, so the rotated config is saved under a wrong alias — while `loadStored()` (default `communityId = "default"`) reads from the correct one.

- **Consequence:** On the next app restart, `EngineWiring.rebuildFromStore()` → `deps.loadStoredConnection()` → `configStore.loadStored()` reads from `"default"` → finds the **old** (pre-rotation) config. The rotation is silently undone. In-memory state is correct (via the immediate `reconfigure()` call on lines 212–219), but on-disk state is wrong.
- **Root cause:** The `Deps.saveRotatedConfig` interface (line 273–277) lacks a `communityId` parameter, and `StoredConnection` (the type returned by `loadStored()`) has no `communityId` field. The implementation improvises with `stored.communityName` — a semantically wrong value.
- **Fix direction:** Add `communityId: String` parameter to `Deps.saveRotatedConfig()`, pass `communityId` from the `runOnce()` scope, and use it in the `configStore.save()` call. Contrast with `AppContainer.rotateCredential()` at `AppContainer.kt:314` which correctly passes `communityId = activeCommunityId`.

#### F3 · MODERATE · Decrypted plaintext not wiped after parse in `openForMember`

**File:** `app/src/main/kotlin/org/openwebdav/messenger/directory/CredentialRotation.kt:58–79`

The `signedBytes` local variable holds the decrypted, raw `JSON ‖ Ed25519-signature` bytes — which contain the new app-password in plaintext inside the JSON. After the JSON is parsed into a `ConnectionConfig` (line 79: `return jsonToConfig(payload)`), `signedBytes` is **not wiped**. The plaintext password lingers in heap memory until GC.

- **Inconsistency:** The seal path (`sealForMember()`, line 36–38) wipes `signSecret` with `fill(0)` in a `finally` block. The open path wipes `boxSec` and `boxPub` (lines 68–69) but not `signedBytes`.
- **The plan claims:** *"wiped from memory after sign"* / *"wiped after sign"* — but the open path of the same round-trip has no wipe. The credential is exposed in memory longer than necessary.
- **Fix direction:** Add `signedBytes.fill(0)` after `jsonToConfig(payload)` in a `finally` block covering lines 65–79.

---

### Review checklist

| Item | Verdict | Evidence |
|------|---------|----------|
| **Plan compliance** | ❌ FAIL — deviation: plan says host public key is embedded; code does not embed it (F1) | Plan `.ai-dev/plans/rotate-with-auto-replace.md:28` "verifier uses the signer public key embedded in the payload"; `CredentialRotation.kt:42–54` nowhere embeds `hostSignPublic` |
| **Product fit** | ✅ | Serves host membership management use case (`docs/product.md` §2 "I want to … manage membership"). The rotation mechanism is the right minimum capability. |
| **Discovery conclude** | N/A | No `docs/product.md` changes in this diff. |
| **Correctness** | ❌ FAIL — two functional defects (F1, F2) | F1 at `EngineWiring.kt:188`; F2 at `EngineWiring.kt:340` |
| **Security** | ❌ FAIL — SC12 over-claim (F1), plaintext credential not wiped (F3) | F1 above; F3 at `CredentialRotation.kt:79` |
| **Honesty** | ❌ FAIL — feature claim "members auto-detect and apply" is false for non-host members (F1) | `EngineWiring.kt:188` — rotation silently dropped for all non-host members |
| **Hygiene & AI slop** | ✅ | No placeholders, no AI chatter, no dead code. Code follows established patterns (`DirectoryService`, `CommunityMetadata`). |
| **Frugality & one-home** | ✅ | `CredentialRotation` is a dedicated class — no crypto scattered. Doc in `docs/protocol/webdav-layout.md` §1.3 points to one spec location. |
| **Doc & prose quality** | ✅ | `webdav-layout.md` addition is clear, structured, follows § conventions. |
| **Contracts regression** | N/A | No product contracts in `docs/contracts/`. |
| **Tests** | ✅ | `CredentialRotationTest.kt` with 4 tests: round-trip, wrong recipient, wrong signature, tampered blob. `EngineWiringTest.kt`, `AppRootTest.kt`, `InviteViewModelTest.kt` — only added interface stubs (`null`/`false`), no existing assertion weakened. |
| **Verification not offloaded** | ✅ | JVM unit tests cover the crypto round-trip. The plan names manual end-to-end as the operator's residual. |
| **Quality tools ran** | ✅ | `./gradlew ktlintCheck lint test` — all green (BUILD SUCCESSFUL, 82 tasks). No `src/quality/` directory — project's native tooling used. |

### Module checklists

- **Threat model** (on, `rich`): Attack surface named in plan and handled — new input (credential form) validated for non-empty (UI `isNotBlank` check, `AppContainer.kt:246`), sealed-box + Ed25519 checks in `openForMember`. Secrets: app-password never logged (`ConnectionConfig.toString()` redacts), sealed immediately. Trust boundaries: `openForMember` verifies signature before accepting config — but F1 means the boundary check uses the wrong key for non-host members. Injection: no shell/SQL/path traversal — member hex from verified identity keys, restricted alphabet. Fail-closed: every failure returns `null`. AuthZ: UI gated behind `isHost`. **Blocking: F1 invalidates the trust-boundary claim for non-host members.**

- **Product advocate** (on, `rich`): Serves the host's membership-management pain (`docs/product.md` §2). The rotation mechanism is the plan's stated minimum capability. Blocked by F1 (non-host members cannot receive rotations — product claim unserved).

- **Test methodology** (on, `rich`): Plan names JVM unit tests (`CredentialRotationTest.kt`) + manual end-to-end. Both exist in the diff. No coverage gap — the crypto round-trip is exercised on JVM (Robolectric). **Blocked by F1 and F2 not being reproducible in the current test suite** (the tests don't exercise the EngineWiring integration with the wrong host key / wrong community ID).

- **UI & UX** (on, `light`): `CommunityListScreen.kt` — Remove button visible only when `isHost`, confirmation dialog → credential form with progress indicator and error state, success dialog. Adequate adverse states (rotation error message). Init order: `isHost` captured once in `remember` (correct for a static property). No external-service config verification path — the rotation form has no "test connection" button before the rotate action (known gap, acceptable for this feature).

- **Research methodology** (on, `light`): No decision-base artifact in diff. N/A.

- **Debug methodology** (on, `rich`): Not a bug-fix change. N/A.

- **Performance** (on, `rich`): Rotation writes N−1 blobs sequentially — linear in member count. Acceptable for small communities. Poll-path check is one read + delete per cycle. No unbounded loops, no N+1 queries. ✅

- **Database** (on, `rich`): No schema changes. `ConnectionConfigStore` is a file-backed Keystore wrapper, not SQL. F2 is the data-integrity finding (wrong storage alias). ✅ aside from F2.

- **i18n** (on, `light`): Single-locale project. All UI strings are inline (plan doesn't require externalization). ✅

- **Concurrency** (on, `light`): `rotateCredential()` is `suspend`, called from `Dispatchers.IO`. `runOnce()` runs on the poll dispatcher — the pre-poll check and poll cycle are sequential in the same coroutine. No shared mutable state between them. ✅

- **Modularity** (on, `rich`): New `CredentialRotation` class in `directory/` follows the existing modular boundary (directory owns community-scoped on-disk operations). New `Deps` methods on `EngineWiring.Deps` extend the existing seam — no cross-module dependency against the grain. `docs/architecture.md` decision list was **not updated** with a credential rotation decision — the plan says "Update docs/architecture.md decision list — add decision for credential rotation mechanism" but the diff shows no change to `docs/architecture.md`. **Finding: documented architecture decision missing.**

- **Plan-adversary** (on, `rich`): Plan names failure modes: "A transport failure during meta/credentials/ check is silently skipped" and "Every failure path in openForMember returns null". The "host public key resolution from directory" is surfaced as out-of-scope — but the plan's stated mitigation ("verifier uses the signer public key embedded in the payload") wasn't implemented, making the plan's own probe into an implementation gap (F1). Forks: none surfaced.

### Additional finding

#### F4 · MODERATE · Architecture decision not documented

**Plan requirement:** `docs/architecture.md` — "add decision for credential rotation mechanism" (`.ai-dev/plans/rotate-with-auto-replace.md:103`). No change to `docs/architecture.md` in the diff.

---

### Summary

Three blocking findings (F1, F2) and two moderate findings (F3, F4). F1 and F2 are functional defects — the feature does not work for its target users (non-host members). F3 is a security hygiene gap. F4 is a documentation gap per the plan's own requirement.
