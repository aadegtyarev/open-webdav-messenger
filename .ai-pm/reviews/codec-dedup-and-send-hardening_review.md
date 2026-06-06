# codec-dedup-and-send-hardening — Pass 1 plan-compliance review

Branch `chore/audit-2026-06-06-fixups` vs `main` (f62064a, 611b219, e3a46e6, d2ca358 + state commit). Read-only.

## Plan compliance

- ✓ **Scenario 1 — Send/publish degrades on native seal failure (C8).** The shared `CommunityDirectoryEngine.publish` (CommunityDirectoryEngine.kt:49–63) now catches `IllegalStateException` (the native seal failure from `aeadEncrypt`) alongside the existing `IllegalArgumentException`, mapping both to the caller's typed `Failed`. Both `DirectoryService.publishEntry` and `ChatDirectoryService.publishChatEntry` route through it. Tests: DirectorySealFailureTest.kt:53 + ChatDirectorySealFailureTest.kt:53 (`publish_degrades_on_native_seal_failure` for BOTH services).
- ✓ **Scenario 2 — Parsing untrusted bytes unchanged (A1).** Three private cursors collapsed onto one shared `message.ByteCursor` (ByteCursor.kt); both codecs consume it (ChatDescriptorCodec.kt:96, DirectoryEntryCodec.kt:79), no local Cursor classes remain. Null-on-overrun / negative-take / limit-ceiling / u64-boundary all pinned by SharedByteCursorTest.kt. Public parse APIs unchanged (existing codec/message suites pass unedited).
- ✓ **Scenario 3 — Directory / chat-directory services unchanged (A2).** Near-clones collapsed onto `CommunityDirectoryEngine<E>` (generic publish+read parameterised by collection segment + grouping-key + verify fn), `CollectionPaths`, `CommunityDirectoryWiring`. Public `DirectoryService`/`ChatDirectoryService`/factory types + method signatures unchanged; the engine never cross-wires the two collections (own resolver + own CollectionPaths per call). Proven by the entire existing directory + chat-directory suites passing unchanged.
- ✓ **Scenario 4 — Name-minting / hashing unchanged (A3).** Base32-lower alphabet centralised to `HashTag.BASE32_LOWER_CHARS` (HashTag.kt:28), referenced from MessageId/ChangeEntry/CollectionPaths. Test: CentralisedBase32AlphabetTest.kt asserts the constant equals the RFC-4648 set and all four former call sites accept/reject identically.
- ✓ **Scenario 5 — AEAD framing unchanged (A4).** `Aead.NONCE_BYTES/TAG_BYTES/KEY_BYTES` derive from the libsodium `AEAD.XCHACHA20POLY1305_IETF_*` constants (Aead.kt:91–103); `ChatKey.KEY_BYTES = Aead.KEY_BYTES` (ChatKey.kt). Test: AeadSizeConstantsTest.kt.
- ✓ **Scenario 6 — Dead zeroize methods gone (B5).** `ChatKey.destroy()` + `Identity.destroy()` removed; grep confirms zero references in main/test/androidTest. Proof = suite compiles + passes unchanged.

**Binding invariant 1 — ZERO existing-test edits.** `git diff --name-status main..HEAD` on `app/src/test` + `app/src/androidTest` shows all six test files status `A` (added); no `M`, no `D`. Load-bearing constraint upheld.

**Binding invariant 2 — behaviour-preserving.** A1/A2 public service/factory types + test-touched signatures unchanged; the full pre-existing suite passes unedited (the plan's primary acceptance gate).

**Binding invariant 3 — C8 additive.** Mapping added narrowly at the publish boundary in the shared engine; catch is specific (`IllegalArgumentException` / `IllegalStateException`, not `Throwable`) so unrelated bugs still surface. Message-build path correctly NOT touched — MessageCrypto/MessageEnvelope/SendWriter unchanged in the diff (deferred to the UI/send feature per Out of scope).

**Binding invariant 4 — out-of-scope respected.** B6 `ChatKey.export()` present and untouched; C9 `Aead.open()` body unchanged (only the constant doc-comment region differs); B7 `ChatPaths` unchanged; D10 `agreeChatKey` unchanged. All confirmed by diff.

**Interaction scenarios.** Both `publish_fails_leaves_read_loop_usable` tests (DirectorySealFailureTest.kt:66, ChatDirectorySealFailureTest.kt:74) set up the post-condition state — induce a seal failure on publish, then a subsequent real read still verifies a pre-existing entry — not a happy path. The `SealFailingNative` fake throws ISE only from `aeadEncrypt`, delegating decrypt/sign/verify to the real backend, so the read path stays intact.

**Test-wiring-parity.** The seal-failure tests assemble `DirectoryService(transport, DirectoryCrypto.create(MessageCrypto(Aead(native)), IdentityCrypto(native)))` — the same `DirectoryCrypto.create → DirectoryService → CommunityDirectoryEngine.publish` chain the production `DirectoryFactory.directoryService(...)` builds (DirectoryFactory.kt:28–30). The only substitutions are the `NativeCrypto` backend (a fault-injecting wrapper over the real LazySodium backend) and a MockWebServer transport — the documented JVM-test seam (DirectoryFactory.kt:7–8), a fault injection at the seam, not a bypass of the registration path. Parity holds.

**Categorical coverage.** The publish-failure-path sibling set (the two directory publish operations) is fully covered — both `DirectoryService` and `ChatDirectoryService` get the C8 mapping AND a degrade test. No sibling silently implemented.

**Stack expectations.** (1) libsodium XChaCha20-Poly1305 sizes (nonce 24 / tag 16 / key 32) single-sourced from the `AEAD.XCHACHA20POLY1305_IETF_*` constants — code agrees; stack-spec test AeadSizeConstantsTest.kt asserts against the libsodium constants (not a self-chosen literal). (2) Base32 RFC-4648 lowercase alphabet — code agrees; CentralisedBase32AlphabetTest.kt asserts against the RFC-4648 set and cites the RFC. No cited rule contradicted; both have stack-spec tests.

**Plan completeness.** Security-bearing project (`docs/threat-model.md` present); the feature touches a security-relevant parse + crypto-failure surface and the plan names `docs/threat-model.md` in "Docs to update" (plan:66) → satisfied. "Stack expectations touched" present with sources. "Interaction scenarios" present. Provenance line cites the audit + PM triage (named feature, not autonomous selection).

**Product Contract.** No Product Contract touched — backend-only internal-quality refactor; every scenario subject is the system/codecs/services, no user-visible behaviour change (the plan states so). Not user-facing → advocate gate `n/a`.

**Diff hygiene.** Every changed source file maps to a named plan task; no cosmetic-noise hunks, no scope expansion.

**Docs.** `docs/architecture.md` + `docs/threat-model.md` intentionally NOT yet updated — both named in the plan's "Docs to update", deferred to pm-architect post-review per plan. Not blocked; the handoff is named so it will fire.

## Definition of Done

- [x] All plan scenarios implemented and tested
- [x] Interaction scenarios have concurrent/post-condition-state tests
- [x] Stack expectations respected; stack-spec tests pass
- [x] Product Contract honored — n/a (no Product Contract touched; backend-only, no user-visible change)
- [x] Pipeline green (`./gradlew test` + `ktlintCheck` + `lint` — exit 0; `connectedAndroidTest` is the manual on-device validator, unchanged scope)
- [x] State file updated (`.ai-pm/state/current.md`)
- [x] Product Impact Report — n/a (no contract touched)
- [x] Docs updates listed in plan are in this branch — deferred to pm-architect per plan; both files named, handoff will fire (not blocking)
- [x] Expected artifacts exist (plan + this review; no contract required — not user-facing)
- [n/a] Product-readiness gate — feature is not user-facing (all scenario subjects are the system); exempt, no advocate artifact required
- [n/a] Validation gate — `software`-kind project (no `Project kind` line)

**DoD: pass**

## Blocking

(none)

## Notes (product)

(none — internal-quality remediation, no user-visible behaviour change. The shared cursor + directory/chat-directory consolidation + base32/AEAD single-source homes still need recording in `docs/architecture.md`, and `docs/threat-model.md` a no-posture-change review; both are named in the plan and owned by pm-architect post-review, so the handoff is in place — flagged here only so the orchestrator does not lose the thread.)

## Verdict

approve

<!-- The trail below is the ONE review section the orchestrator owns, not pm-plan-checker.
     See the "Edit-ownership rule" in `workflow/enforcement.md` — the Pass-2 code-review
     trail is the single carve-out to "orchestrator does not edit content artefacts". -->
## Code review findings
(populated by orchestrator from code-review output; pm-coder reads and fixes these)

Pass 2 technical review — built-in `code-review` engine, high depth, 3 finder angles (cursor/constant parity, directory-collapse correctness, line-scan/cleanup/altitude) + verification. **0 correctness findings** — all three angles independently confirmed the refactor is behaviour-preserving and that C8 *closes* a latent gap (the old `DirectoryService`/`ChatDirectoryService` caught only `IllegalArgumentException`, so a native seal `IllegalStateException` would have propagated uncaught; the shared engine now maps it to typed `Failed`). Verified against the real lazysodium-android jar: `XCHACHA20POLY1305_IETF_NPUBBYTES=24 / _ABYTES=16 / _KEYBYTES=32`, so the A4 single-sourcing introduces no value drift. Cursor overrun semantics (null-on-overrun, consumed via `?: return reject`), the `limit` ceiling, `u64()` big-endian read, the base32 alphabet, the §10/§11 collection isolation, the grouping keys, the §11 dm-drop, and the secret-wipe-in-finally timing are all byte-for-byte preserved. No leaky `<E>` abstraction (zero per-service `if/when` in the engine).

Non-blocking cleanup observation (NOT a correctness issue; recorded for the PM): the collection-segment literal (`"directory"` / `"chat-directory"`) now lives both as the `DirectoryPaths.DIRECTORY` / `ChatDirectoryPaths.CHAT_DIRECTORY` const **and** in the `CollectionPaths(...)` the engine re-mints — the two agree, so behaviour is identical, but the segment is not single-sourced and the `*Paths` faces are now pure forwarding layers kept alive mainly as a test-convenience API. A small follow-up tidy (have the service pass its one `CollectionPaths`/segment home through) would complete the dedup; left as a follow-up, not blocking.

## Code review: 2026-06-06 — passed
