# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

ui-chat-surface (the first user-facing feature) — PM-approved plan 2026-06-06, branch `feature/ui-chat-surface`. A thin working vertical slice that turns the headless engine into a usable app: owner connects a WebDAV disk + names a community → the app auto-creates the always-on community chat (random key) + switches sync on; members join via a string/QR **invite** that carries disk access + chat-id + random chat key + community name (hidden from the member); both read + send plain-text messages, online/offline. Plan: `docs/features/ui-chat-surface_plan.md`. Contract: `.ai-pm/contracts/community-chat.md`.

## Status

coding — implementation landed; Pass-1 plan-compliance review (`.ai-pm/reviews/ui-chat-surface_review.md`) returned `request-changes`. pm-coder has now closed the BLOCKING test gaps (camera-fallback test, manifest stack-spec test, the four Compose `createComposeRule` UI tests). Pipeline green (`./gradlew test` + `ktlintCheck` + `lint`). Remaining review items (docs updates, state refresh) are pm-architect / orchestrator scope.

## Done

- Plan written + PM-approved (two scope reshapes: owner-only disk creds + string/QR invite; random key inside the invite; create-community auto-creates the community chat).
- Product Contract drafted (`.ai-pm/contracts/community-chat.md`) — PM-validated draft.
- Stack-notes extended (pm-stack-researcher, 2026-06-06): QR generate (`com.google.zxing:core`) + scan (`com.journeyapps:zxing-android-embedded`) + CAMERA permission idiom; ML Kit rejected (proprietary + Play-Services); no new validator.
- Backlog updated: reserved-owner-marker design note (owner-migration base); new future feature "app self-update via the community disk" (security-bearing, owner-signed APK).
- Prior task x25519-identity shipped as v0.9.0 (PR #13, merged to main 73e3045).
- pm-coder: ui-chat-surface implementation landed (commits 0ad4d09 / d2e79c2 / 5742519).
- pm-coder: Pass-1 review test gaps closed — `JoinViewModelTest.camera_denied_falls_back_to_paste`, `ManifestCameraStackSpecTest.manifest_declares_camera_not_required`, and the four Compose `createComposeRule` UI tests (CreateCommunityScreenTest / JoinScreenTest / InviteScreenTest / ChatFeedScreenTest). compose-ui-test-manifest moved to debugImplementation; release unit tests exclude the screen tests (debug-only manifest host). Pipeline green.

## Remaining

- pm-architect: post-coding doc updates the plan's "Docs to update" requires — `docs/architecture.md` (wiring + at-rest storage + `owdm1:` format + reserved owner-marker + ZXing deps), `docs/threat-model.md` (bearer-invite + device-local secret storage + CAMERA + untrusted-text rows; bump Last reviewed), `docs/user-journeys.md` (onboarding journey), `README.md` (quick-start). (Review Blocking item 4.)
- Review loop: re-run pm-plan-checker Pass 1 to confirm the closed test gaps, then code-review Pass 2.
- Then regenerate product-map + append contract Built/changed-by. Then ship (pr-prep) on PM go.

## Next step

Re-run pm-plan-checker (Pass 1) to confirm the test gaps are closed, and spawn pm-architect for the post-coding doc updates (Blocking item 4).

## Validation

Plan Test plan: JVM tests (`./gradlew test`, Robolectric in place) for invite codec / wiring / send / dedup; Compose UI tests via `createComposeRule` for the screens; `./gradlew ktlintCheck` + `./gradlew lint`. Live-camera QR decode is a manual on-device step (same class as connectedAndroidTest, decision 8). No new pipeline validator.

## Notes

GitHub remote: `git@github.com:aadegtyarev/open-webdav-messenger.git` (private). Current version: v0.9.0. This is the FIRST user-facing feature — all prior work was backend substrate. Bearer-invite is an accepted MVP limit (backlog). Owner-migration: design reserved only, not built. Public/passphrase chats, rich rendering, discovery UI: deferred to later slices.
