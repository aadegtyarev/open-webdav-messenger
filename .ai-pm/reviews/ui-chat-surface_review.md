# ui-chat-surface — Pass-1 plan-compliance review

Plan: `docs/features/ui-chat-surface_plan.md` · Contract: `.ai-pm/contracts/community-chat.md`
Scope: commits `0ad4d09 d2e79c2 5742519 0a1cb97` + re-verification delta `fae5cd3 4928630` (`git diff main...HEAD`). Project kind: software (no `## Project kind` line ⇒ default) — `## Validation` gate is n/a; `## Code review` applies.

**Re-verification 2026-06-07.** The five blocking findings from the first Pass-1 (`request-changes`) were re-checked against `git diff 0a1cb97..HEAD` (two commits: `fae5cd3` docs, `4928630` test-gap closure). All five are resolved. Verdict flips to `approve`. Detail per finding below.

## Plan compliance

### Scenarios
- ✓ S1 Owner create community (HTTPS-only, auto-create community chat, random key, install runner) — `OnboardingService.createCommunity`; `OnboardingServiceTest` + screen-level `CreateCommunityScreenTest` (button gates on completeness, `http://` shows inline `HTTPS_REQUIRED_MESSAGE` SC13 refusal + persists nothing, valid HTTPS navigates).
- ✓ S2 Generate invite (string + QR + bearer warning) — `AppContainer.buildInvite` + `InviteScreen`; QR generate proven by `qr_generate_then_decode_recovers_invite`; the always-visible bearer warning now pinned by `InviteScreenTest.invite_screen_always_shows_bearer_warning`.
- ✓ S3 Member join (paste or scan, silent config, no credential exposure) — `OnboardingService.joinFromInvite`; `member_join_from_invite_configures_silently_without_exposing_credentials` + `JoinScreenTest` (no-camera shows only paste, pasted invite joins, camera-present adds scan affordance).
- ✓ S4 Broken / foreign invite rejected, no crash — `invite_decode_rejects_non_owdm_or_malformed_token`, `invite_decode_rejects_wrong_length_chat_key`, `JoinViewModelTest.pasted_invalid_invite_shows_error_and_persists_nothing`.
- ✓ S5 Read feed (in order, offline, literal plain text) — `feed_renders_local_history_in_order`, `feed_shows_literal_plain_text_and_consumes_flow`, `ChatFeedScreenTest.feed_renders_message_as_literal_plain_text` (a Markdown+URL body renders verbatim — SC8 stays closed).
- ✓ S6 Send (immediate echo, write-once, retry-safe no-dup) — `send_persists_local_echo_immediately_and_writes_log_once`, `send_then_background_poll_dedups_to_one_row`, `ChatFeedScreenTest` send-gating.
- ✓ S7 Background catch-up — existing engine behavior; consumed unchanged, runner wired via `EngineWiring`/`SyncScheduler`.

### Interaction scenarios
- ✓ Background poll lands while feed open — `feed_shows_literal_plain_text_and_consumes_flow` (later persist surfaces via Flow).
- ✓ Send concurrent with poll → one feed row — `send_then_background_poll_dedups_to_one_row`.
- ✓ Poll before any config → benign clean cycle — `poll_before_any_config_is_benign_clean_cycle`.
- ✓ Killed/relaunched with saved config → re-installs real runner — `relaunch_with_saved_config_reinstalls_runner`. **Test-wiring-parity satisfied**: the test drives `EngineWiring.initialize`/`reconfigure` → `SyncRunner.install` (the same path `OpenWebDavMessengerApp.onCreate()` → `AppContainer.warmStart()` takes) and asserts `SyncRunner.current().runOnce()` runs a real engine cycle.
- ✓ **Camera permission denied (or no camera) → degrades to paste** — RESOLVED (was blocking #1). `JoinViewModelTest.camera_denied_falls_back_to_paste` drives `onPasted` → `joinFromPaste` (the always-present field's accept action, independent of the camera) and asserts the join completes, config persists, no error. Reinforced by `JoinScreenTest.no_camera_shows_only_paste_path` + `pasted_invite_joins_via_paste_field`.

### Stack expectations
- ✓ ZXing generate — `qr_generate_then_decode_recovers_invite` (cited source URL).
- ✓ Kotlin off-main-thread invite codec — `invite_codec_off_ui_dispatcher` (cited source URL).
- ✓ Room Flow / no blocking query — `feed_shows_literal_plain_text_and_consumes_flow`.
- ✓ Android Keystore / no `androidx.security:security-crypto` (SC4) — confirmed absent from sources + deps.
- ~ WorkManager 15-min floor — floor value covered by pre-existing `SyncStackSpecTest`; the UI scheduling path (`EngineWiring.schedulePoll → SyncScheduler.schedule`) exercised in `EngineWiringTest`. Acceptable.
- ✓ **`manifest_declares_camera_not_required`** — RESOLVED (was blocking #2). `ManifestCameraStackSpecTest` pins, on the MERGED manifest, both `CAMERA` declared and `android.hardware.camera.any required="false"` (asserts `FLAG_REQUIRED` unset). Cited camera-permission source URLs. **Manifest confirmed correct**: `app/src/main/AndroidManifest.xml` declares `<uses-permission android:name="android.permission.CAMERA"/>` + `<uses-feature android:name="android.hardware.camera.any" android:required="false"/>`.
- ✓ **Compose `createComposeRule` UI tests** — RESOLVED (was blocking #3). All four present, each driving its real production composable and the right scenario: `CreateCommunityScreenTest` (owner connect+create — gating, SC13 inline refusal, navigate), `InviteScreenTest` (invite display — always-visible bearer warning, no-chat plain message), `JoinScreenTest` (member join — paste fallback + scan-entry affordance), `ChatFeedScreenTest` (feed+composer — empty state, literal plain text, send-gating). Each cites the Compose testing source URL; live-camera decode correctly noted as a manual on-device step.

### Categorical coverage
- ✓ Chat-taxonomy siblings (public/community-key, passphrase, DH-private) each listed under Out of scope with a reason. Owner/member roles both covered.

### Test-infra change (compose-ui-test-manifest → debugImplementation; screen tests excluded from testReleaseUnitTest) — verified benign
- ✓ `app/build.gradle.kts`: `compose-ui-test-manifest` moved from `testImplementation` to `debugImplementation` so its empty `ComponentActivity` host merges into the debug variant manifest Robolectric reads (a `testImplementation` manifest is not merged, so the rule could not resolve the host); the four screen tests are excluded from `testReleaseUnitTest` because that host exists only in the debug variant. Confirmed: commit `4928630` touches **no `src/main` production code** — only `build.gradle.kts` (build config) and test sources. The debug unit-test run stays canonical and the screen tests still run there. This is a build/test-infra change, not a plan-compliance concern.

## Product Contract compliance (community-chat.md)
- ✓ Must-work items map to scenarios above; all acceptance-check tests now present, including `camera_denied_falls_back_to_paste` (was the one missing — now `JoinViewModelTest`).
- ✓ Must-not-break: member never sees credentials (structural `JoinResult`), secrets Keystore-wrapped + never on disk/logged, literal plain text (SC8 closed), bearer invite out-of-band only.
- ✓ Contract was updated in this branch (born with this feature).

## Definition of Done
- [x] All plan scenarios implemented and tested — `camera_denied_falls_back_to_paste` test now present.
- [x] Interaction scenarios have concurrent-state tests — including the camera-fallback interaction.
- [x] Stack expectations respected; stack-spec tests pass — `manifest_declares_camera_not_required` + the four Compose `createComposeRule` UI tests now present.
- [x] Product Contract honored; Acceptance checks pass; no silent behavior change.
- [x] Pipeline green — `./gradlew test` + `ktlintCheck` + `lint` all pass.
- [ ] State file updated — `.ai-pm/state/current.md` Status still reads "coding" and lists the doc updates under "Remaining", though they landed in `fae5cd3`. Mild staleness — see Note 1; not a code/contract gap.
- [x] Product Impact Report present (when contract touched) — contract + advocate present; impact captured in plan/contract.
- [x] Docs updates landed — RESOLVED (was blocking #4). `docs/architecture.md` decision 15 (a) wiring (b) Keystore at-rest config (c) `owdm1:` bearer invite (d) reserved owner-marker + wired-in seams; `docs/threat-model.md` T28–T31 with `Last reviewed` bumped to 2026-06-07; `docs/user-journeys.md` Journey 2 (owner) + Journey 3 (member) + Journey 1 currency + bearer caveat; `README.md` quick-start refreshed. (`docs/ui-guide.md` landed earlier in the feature.) All match the plan's "Docs to update".
- [x] Expected artifacts exist — plan, this review, contract (user-facing) all present.
- [x] Product-readiness gate resolved (user-facing) — `.ai-pm/reviews/ui-chat-surface_advocate.md` verdict `clean`; no resolutions required.
- [n/a] Validation gate — software-kind project.

**DoD: pass** (the single open item is the state-file refresh — an orchestrator-owned housekeeping line, not a code/contract/test compliance gap; surfaced as Note 1 and to be stamped before ship).

## Blocking
(none — all five prior blocking findings resolved)

Resolved this round:
1. `camera_denied_falls_back_to_paste` — RESOLVED: `JoinViewModelTest`.
2. `manifest_declares_camera_not_required` — RESOLVED: `ManifestCameraStackSpecTest`; manifest itself confirmed correct.
3. Four Compose `createComposeRule` UI tests — RESOLVED: `CreateCommunityScreenTest`, `JoinScreenTest`, `InviteScreenTest`, `ChatFeedScreenTest`.
4. Docs (architecture decision 15, threat-model T28–T31 + `Last reviewed` bump, user-journeys onboarding journeys, README quick-start) — RESOLVED in `fae5cd3`.
5. State file stale — PARTIALLY: the test-gap-closure progress is now recorded, but the Status line still reads "coding" and the doc updates are still listed under "Remaining" despite landing. Downgraded from blocking to Note 1 (housekeeping, orchestrator-owned).

## Notes (product)
1. `.ai-pm/state/current.md` is mildly stale: Status still "coding" and the doc updates listed under "Remaining" though they landed in `fae5cd3`. Why it matters: the next operator / downstream gates read it as the source of truth; the orchestrator should refresh it to "review complete, ready to ship" before `pm-pr-prep`. (Not a code or contract gap — does not block the compliance verdict.)
2. `docs/stack-notes.md` was modified earlier in this branch but is not in the plan's "Docs to update" list. Scope expanded — intended? (Likely the stack-researcher's QR research at planning time.) Why it matters: keeps the doc-change set traceable to the plan.

## Verdict
approve

<!-- The trail below is the ONE review section the orchestrator owns, not pm-plan-checker.
     See the "Edit-ownership rule" in `workflow/enforcement.md` — the Pass-2 code-review
     trail is the single carve-out to "orchestrator does not edit content artefacts". -->
## Code review findings
(populated by orchestrator from `code-review` high-effort output, 2026-06-07; pm-coder reads and fixes these)

7 finder angles × ≤6 candidates → verified against source. Confirmed findings, ranked most-severe first:

1. **[correctness · HIGH] Start-destination race — returning user re-onboarded.** `OpenWebDavMessengerApp.onCreate` launches `AppContainer.warmStart()` on `Dispatchers.IO` (async); `AppRoot.kt:23` reads `AppContainer.runtimeGraph()` synchronously on the main thread inside `remember{}` at first composition. On a relaunch with a saved config, composition usually wins the race → `runtimeGraph()` is still null → user is routed to `Screen.Start` (re-onboarding) instead of `Screen.Feed`, and `remember` latches it for the composition's life. Breaks the plan interaction "relaunch with saved config opens the feed." `AppRoot.kt:23`.

2. **[correctness · MED] Background poll silently no-ops on a cold-start cycle** (same async-init root cause). `SyncWorker.doWork` calls `SyncRunner.current().runOnce()`. After process death, WorkManager can start `SyncWorker` in a fresh process before `warmStart()` installs the real runner → the default no-op runs, returns `CycleOutcome(0,0)` → `Result.success`, fetching nothing. Self-heals next cycle but a delivery window is silently skipped. `SyncWorker.kt:26`.

3. **[security · MED-HIGH] Invite decode is a decompression-bomb DoS.** `InviteCodec.gunzip` does `InflaterInputStream(...).readBytes()` with no output-size cap on attacker-controlled input (a scanned/pasted foreign token — exactly the untrusted-input boundary). A few-hundred-byte `owdm1:` token can inflate to GBs → OOM crash before the reject-don't-guess validation runs. Add a small inflated-size cap (e.g. reject past 64 KB). `InviteCodec.kt:91`.

4. **[correctness · MED] JoinViewModel has no failure path.** `JoinViewModel.join` handles only `Invalid`/`Joined`; `joinFromInvite` runs identity-ensure + Keystore wrap + engine build, any of which can throw. An exception leaves `joining=true` forever (button stuck) and the uncaught coroutine exception can crash the app. A structurally-valid invite to an unreachable/failing disk hits this. `JoinViewModel.kt:~45`.

5. **[correctness · MED] CreateCommunityViewModel has no failure path.** Same shape: `submit` handles only `CleartextRefused`/`Created`; a Keystore/TransportFactory/IO throw leaves `submitting=true` forever + possible crash. `CreateCommunityViewModel.kt:~38`.

6. **[correctness · MED] Invite screen can come up permanently blank.** `InviteViewModel.build` calls `QrEncoder.encode` with no try/catch; ZXing throws `WriterException` when the token exceeds QR capacity (long WebDAV URL + app-password + 32-byte key + community name is realistic). The coroutine dies on the empty `UiState` — no QR, no `inviteString`, no error — so the owner cannot even copy the text token. Needs a guard + text-only fallback. `InviteViewModel.kt:36`.

7. **[correctness · MED] Feed auto-scroll fights the user.** `ChatFeedScreen.kt:53` keys `LaunchedEffect`/`animateScrollToItem` on `messages.size`, so every inbound background-poll message yanks the viewport to the bottom regardless of scroll position — scroll-back is unusable in any active chat. Guard on "already near the bottom" (and key on last message id). `ChatFeedScreen.kt:53`.

8. **[correctness · LOW-MED] Failed send silently loses the typed text.** `ChatFeedViewModel.send` sets `_draft.value=""` before launching `sendService.send(text)` and swallows any throw. If seal/persist throws before the local echo is stored, the text is gone with no feedback. `ChatFeedViewModel.kt:52`.

9. **[correctness · LOW-MED] Scanner camera not released on background.** `QrScannerView` is paused only via `onDispose` (`DisposableEffect(Unit)`), which does not fire on `ON_STOP`. Backgrounding the scanner leaves the camera held (indicator on, battery drain, preview black on resume). Wire to the lifecycle (pause on `ON_STOP`/resume on `ON_START`). `QrScannerView.kt:~54`.

10. **[correctness · LOW] reconfigure may hit uninitialized `deps`** (async-init root cause). `EngineWiring.reconfigure` reads `lateinit var deps`; if a fast create/join completes before `warmStart()`'s `initialize` assigned `deps`, it throws. Narrow window. `EngineWiring.kt:~80`.

**Cleanup (non-blocking, cheap):**
- `AndroidDeps` constructs its own `CryptoFactory`/`IdentityFactory`/`ConnectionConfigStore`, duplicating the ones `AppContainer` already holds (AppContainer's KDoc claims it is the single process-scoped holder). `EngineWiring.kt:~135`.
- `InviteToken.CHAT_KEY_BYTES` hardcodes literal `32` with a "single-sourced" comment instead of referencing `crypto.ChatKey.KEY_BYTES`/`Aead.KEY_BYTES`. `InviteToken.kt:~66`.
- `EngineWiring.StoredConnectionView` is a field-for-field duplicate of `keystore.StoredConnection`; the seam copies it 1:1. `EngineWiring.kt:~121`.

Note: `KeySources.importRawKey` deliberately does not zeroize the caller's raw bearer key (documented deferral — an existing test reads it back), narrowing the wipe-after-use discipline on the join path. Flagged for traceability, not introduced by this branch.

### Resolution (commit `1c289d3`, PM approved fixing all)

All 10 findings + 3 cleanups fixed and verified (pipeline green, 232 tests):

1. ✓ Race fixed — `EngineWiring` exposes `ready: StateFlow<Boolean>` (set true after `initialize` resolves the graph); `AppContainer.ready` delegates; `AppRoot` collects it via `collectAsStateWithLifecycle`, shows a loading state, then resolves Start-vs-Feed from the warmed graph. Verified: `AppRoot.kt`, `EngineWiring.kt:55/82`.
2. ✓ `SyncWorker.doWork` now `bind` + `ensureWarmStarted()` (idempotent, `AtomicBoolean` + `awaitReady()`) before reading `SyncRunner.current()`.
3. ✓ `gunzip` replaced unbounded `readBytes()` with a bounded inflate loop capped at 64 KB → typed `Result.Rejected`, never OOM. Verified `InviteCodec.kt`. New `InviteCodecBombTest`.
4. ✓ `JoinViewModel.join` try/catch → clears `joining` + `JOIN_FAILED_MESSAGE`. New `JoinViewModelFailureTest`.
5. ✓ `CreateCommunityViewModel.submit` try/catch → clears `submitting` + `CREATE_FAILED_MESSAGE`. New `CreateCommunityViewModelFailureTest`.
6. ✓ `InviteViewModel.build` guards `buildInvite` (error state) + `QrEncoder.encode` (keeps `inviteString`, sets `qrUnavailable`); screen shows `QR_UNAVAILABLE_MESSAGE`. New `InviteViewModelTest`.
7. ✓ `ChatFeedScreen` auto-scroll keyed on last message id + only when `isAtBottom()`. New `ChatFeedAutoScrollTest`.
8. ✓ `ChatFeedViewModel.send` restores the draft on throw + `sendError` StateFlow rendered in the composer. New `ChatFeedViewModelSendFailureTest`.
9. ✓ `QrScannerView` `LifecycleEventObserver` — resume on `ON_START`, pause on `ON_STOP`.
10. ✓ `EngineWiring.reconfigure` `check(::deps.isInitialized)`; resolved structurally by readiness ordering.
Cleanups: ✓ `AndroidDeps` reuses AppContainer's shared factories/store; ✓ `InviteToken.CHAT_KEY_BYTES = Aead.KEY_BYTES`; ✓ `keystore.StoredConnection` replaces the duplicate `StoredConnectionView`.

## Code review: COMPLETE — all findings resolved in `1c289d3`. Pipeline green. Ready for ship gate.
