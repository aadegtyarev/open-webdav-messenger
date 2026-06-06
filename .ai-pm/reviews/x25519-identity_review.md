# x25519-identity — Pass 1 plan-compliance review

Branch `feature/x25519-identity` (commits 06bcb11, 458a257, e05526a since main). Read-only compliance check against `docs/features/x25519-identity_plan.md` + arch note `.ai-pm/arch/x25519-identity_arch.md`.

## Plan completeness
- ✓ "Stack expectations touched" present with source URLs (plan 32–37) — feature touches the libsodium key-agreement/KDF + generic-hashing stack components.
- ✓ "Interaction scenarios" present (plan 39–43) — feature touches shared mutable state (`ChatKeyStore`) + async (poll/send in flight).
- ✓ Security-bearing project (`docs/threat-model.md` exists); feature touches `### Security-relevant surfaces` "Cryptography / key management" (key derivation); plan lists `docs/threat-model.md` in "Docs to update" (plan 64, A2/T-row). Threat-model lifecycle satisfied.
- ✓ `Source:` is "PM feature selection" — not `selected autonomously`; selection-citation backstop does not fire.

## Plan compliance
- ✓ Scenario 1 (distinct key per chat-id from peer's public key; symmetric, no secret exchanged) — implemented `IdentityCrypto.deriveRemoteChatKey` (DH→keyed-BLAKE2b, v2 context). Tests `derive_remote_chatkey_distinct_per_chat_id`, `derive_remote_chatkey_symmetric_for_fixed_chat_id` at `app/src/test/kotlin/org/openwebdav/messenger/identity/RemoteChatKeyTest.kt`.
- ✓ Scenario 2 (two chats / same pair → distinct keys, D10 fix; A-sealed Rejected under B's key) — tests `derive_remote_chatkey_distinct_per_chat_id`, `cross_chat_isolation_end_to_end` (asserts `OpenResult.Rejected`).
- ✓ Scenario 3 (derived key stored + loadable by chat-id) — `RemoteChatProvisioner.provision` → `ChatKeyStorePort.store`; test `provisioning_stores_a_loadable_key` drives `provision()` then `store.load(chatId)`.

## Binding-constraint verification
1. **Zero existing-test edits** — ✓ `git diff --name-status main..HEAD` over `app/src/test` + `app/src/androidTest` shows only `A` (two added files: `RemoteChatKeyTest.kt`, `RemoteChatProvisionerTest.kt`). No `M`/`D`.
2. **D10 fix correctness vs arch note** — ✓ `deriveRemoteChatKey` builds the message `REMOTE_CHATKEY_KDF_CONTEXT ("owdm/x25519-chatkey/v2") ‖ 0x1F ‖ chatId.toByteArray(UTF_8)`, mirroring `KeySources.knownKey`'s `‖ 0x1F ‖ chatId`. DH shared secret is the keyed-BLAKE2b **key**, the v2 message is hashed; raw DH output never used as the key (asserted by `derivation_does_not_use_raw_dh_output_as_key`). New v2 context companion-constant; v1 untouched. Shared secret + derived intermediate zeroized in `deriveFromDh`'s `finally`.
3. **agreeChatKey signature unchanged** — ✓ 2-arg signature intact; body now delegates to private `deriveFromDh(..., CHATKEY_KDF_CONTEXT)` (v1), so output is byte-identical; only KDoc changed. Existing DH-symmetry tests run unedited.
4. **Provisioning-seam dependency arrow** — ✓ `RemoteChatProvisioner` lives in the `directory` package (which already depends on `identity`); no `identity → directory` edge introduced. Returns typed `ProvisionOutcome` (Provisioned/Failed); narrow `catch (IllegalStateException)` at the native-crypto boundary, no uncaught throw (C8). Uses `ChatKeyStorePort.store`/`load`; box-secret copy wiped in `finally`.
5. **Test-plan coverage** — ✓ all eight named tests present and matching: D10-regression, symmetry, derived≠bare-pairwise, cross-chat isolation e2e (Rejected), wiring-parity stores-loadable-key, degrades-on-native-failure (typed), idempotent/isolated, + 2 stack-spec (raw-DH cites authenticated_encryption URL, v2-context-binds-chat-id cites generic_hashing URL).
6. **Out of scope respected** — ✓ no QR/fingerprint UI, no `agreeChatKey` signature change, no rotation, no UI. `importRawKey` input-wipe correctly DEFERRED with a KDoc note (an existing test reads `raw` after import — wiping would change observable behaviour); `openSealed(blob, identity)` boxPub wipe added additively (local var, no caller-observable change).

## Test-wiring-parity
- ✓ Not a registration/singleton/side-effect-wiring feature — correctness is pure crypto + a constructor-injected coordinator. The production entry point is `RemoteChatProvisioner.provision()`; the wiring-parity test constructs `RemoteChatProvisioner(identityCrypto, store)` (identical to `DirectoryFactory.remoteChatProvisioner` = `RemoteChatProvisioner(wiring.identityCrypto(), chatKeyStore)`) and drives `provision()` directly — not a hand-rolled derive+store. `InMemoryChatKeyStore` stands in for the device-bound `ChatKeyStore` via the shared `ChatKeyStorePort` seam (real Keystore is `connectedAndroidTest`-only per stack-notes); both honor the overwrite-by-chat-id contract. The native-failure fake throws the same `IllegalStateException` the real `LazySodiumCrypto.boxBeforeNm` `check()` throws — the typed-failure test exercises the real failure path. Wiring-parity satisfied.

## Interaction scenario coverage
- ✓ Concurrent provision / in-flight other chat → isolation: `provision_idempotent_and_isolated` pre-stores a key under a different chat-id, provisions chat-x, asserts the other key is untouched (the concurrent-state condition, not a bare happy path).
- ✓ Two members provision same chat concurrently → same key + idempotent re-provision: `provision_idempotent_and_isolated` re-provisions same (peer, chatId) → identical stored key; symmetry (both members → same key) covered by `derive_remote_chatkey_symmetric_for_fixed_chat_id`.
- ✓ Directory entry later superseded → stored key is source of truth: covered by determinism + idempotency (the seam derives from the public key at call time; stored key is not retro-changed). Determinism asserted in `per_chat_kdf_context_binds_chat_id` (same chat-id re-derives identical key) and `provision_idempotent_and_isolated`.

## Stack expectations compliance
- ✓ "raw shared secret NEVER used directly as the AEAD key" (authenticated_encryption URL) — DH output is the BLAKE2b key, not the AEAD key; `derivation_does_not_use_raw_dh_output_as_key` codifies derived ≠ raw `boxBeforeNm` bytes.
- ✓ "distinct, versioned context per derivation purpose" (generic_hashing URL) — new v2 context for the chat-id-bound purpose, v1 retained for the bare primitive; `per_chat_kdf_context_binds_chat_id` codifies the chat-id is part of the hashed input.

## Product Contract
- No Product Contract touched. Backend substrate (no UI; scenario subjects are "the engine" / "a derived key" — the system, not a human role). `.ai-pm/contracts/` does not exist; no user-facing feature → no contract required.

## Definition of Done
- [x] All plan scenarios implemented and tested
- [x] Interaction scenarios have concurrent-state tests
- [x] Stack expectations respected; stack-spec tests pass
- [x] Product Contract honored; Acceptance checks pass; no silent behavior change (n/a — no Product Contract touched)
- [x] Pipeline green (`./gradlew test` + `ktlintCheck` + `lint` BUILD SUCCESSFUL; `connectedAndroidTest` = manual on-device gate, decision 8 — change is additive, `ChatKeyStore` method signatures unchanged)
- [x] State file updated (`.ai-pm/state/current.md`)
- [x] Product Impact Report present (n/a — no contract touched)
- [x] Docs updates listed in plan are in this branch — N/A by design: plan names `docs/architecture.md` (decision 10), `docs/threat-model.md` (A2/T-row, Last reviewed 2026-06-06), `docs/user-journeys.md`, all explicitly "Updated by pm-architect post-coding"; the post-coding handoff is named so it will fire. Not blocked per the review brief.
- [x] Expected artifacts exist (plan, this review; no contract — not user-facing)
- [n/a] Product-readiness gate (non-user-facing — every scenario subject is the system)
- [n/a] Validation gate (software-kind project — no `## Project kind:` line in CLAUDE.md ⇒ software)

**DoD: pass**

## Blocking
(none)

## Notes (product)
1. The remote private chat capability now exists in the engine but has no member-facing surface yet — a person cannot pick a peer + chat-id or see the chat until the chat-creation/invite UI feature lands. The plan scopes this deliberately (backend seam only). Why it matters: this slice silently enables a real privacy win (private chat with a directory peer, no secret exchanged), but it is invisible to users until the UI feature is prioritized — worth keeping that UI follow-up visible on the roadmap so the value actually reaches members.

## Verdict
approve

<!-- The trail below is the ONE review section the orchestrator owns, not pm-plan-checker.
     See the "Edit-ownership rule" in `workflow/enforcement.md` — the Pass-2 code-review
     trail is the single carve-out to "orchestrator does not edit content artefacts". -->
## Code review findings
(populated by orchestrator from code-review output; pm-coder reads and fixes these)

Pass 2 technical review — orchestrator-driven: direct read of the security-critical derivation + one adversarial verifier over the provisioner/seam/wiring. **0 correctness findings.** Verified: the v2 derivation message is `"owdm/x25519-chatkey/v2" ‖ 0x1F ‖ chatId` with the DH shared secret as the keyed-BLAKE2b key (raw DH never the AEAD key), nested-`finally` zeroization of shared secret + intermediate, symmetric via `boxBeforeNm`, unambiguous canonicalization (fixed context contains no 0x1F). Provisioner: box-secret copy wiped on every path incl. the error branch; `catch (IllegalStateException)` is correctly narrow and — under real inputs — no uncaught crypto exception can escape (lengths are guaranteed by the `Identity` / `DirectoryEntry` constructors, so the native `require()` paths are unreachable). `ChatKeyStorePort` is a pure extracted interface (no behavioural change); the production wiring (`DirectoryFactory.remoteChatProvisioner`) and the wiring-parity test drive the same `provision()` constructor/entry point.

Non-blocking observations (NOT acted on — recorded):
1. The provisioner `try` wraps both `deriveRemoteChatKey` and `chatKeyStore.store(...)`, so a production Keystore `store()` `IllegalStateException` would be caught and labelled a "native crypto failure" — a slightly inaccurate diagnostic label, but still a typed `Failed` with the secret copy wiped (no behavioural defect; arguably correct to treat a store failure as a provisioning failure too).
2. `store(chatId, key)` overwrites source-agnostically; the "don't provision a chat-id that already names a passphrase/random chat" rule is doc-contract-only (no code guard) — a documented future-caller footgun, out of scope for this seam.
3. **Pre-existing (NOT this PR):** `crypto/KeySources.kt` is classified `data` by `file(1)` in both `main` and this branch — a latent encoding quirk unrelated to this feature (the coder's only KeySources change was an `importRawKey` KDoc note). Worth a cosmetic cleanup; backlog.

## Code review: 2026-06-06 — passed
