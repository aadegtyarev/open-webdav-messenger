# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

ui-chat-surface (the first user-facing feature) — PM-approved plan 2026-06-06, branch `feature/ui-chat-surface`. A thin working vertical slice that turns the headless engine into a usable app: owner connects a WebDAV disk + names a community → the app auto-creates the always-on community chat (random key) + switches sync on; members join via a string/QR **invite** that carries disk access + chat-id + random chat key + community name (hidden from the member); both read + send plain-text messages, online/offline. Plan: `docs/features/ui-chat-surface_plan.md`. Contract: `.ai-pm/contracts/community-chat.md`.

## Status

coding — pre-coding gates PASSED: product-readiness advocate `clean` (`.ai-pm/reviews/ui-chat-surface_advocate.md`); arch note written (`.ai-pm/arch/ui-chat-surface_arch.md`, 6 structural choices) + `docs/ui-guide.md` authored. pm-coder running (background) on branch `feature/ui-chat-surface`.

## Done

- Plan written + PM-approved (two scope reshapes: owner-only disk creds + string/QR invite; random key inside the invite; create-community auto-creates the community chat).
- Product Contract drafted (`.ai-pm/contracts/community-chat.md`) — PM-validated draft.
- Stack-notes extended (pm-stack-researcher, 2026-06-06): QR generate (`com.google.zxing:core`) + scan (`com.journeyapps:zxing-android-embedded`) + CAMERA permission idiom; ML Kit rejected (proprietary + Play-Services); no new validator.
- Backlog updated: reserved-owner-marker design note (owner-migration base); new future feature "app self-update via the community disk" (security-bearing, owner-signed APK).
- Prior task x25519-identity shipped as v0.9.0 (PR #13, merged to main 73e3045).

## Remaining

- pm-architect: produce the arch note (`.ai-pm/arch/ui-chat-surface_arch.md`) for the structural choices (app-startup wiring home, invite codec home, role model, scanner integration, reserved `meta/community.json` owner-marker seam, ViewModel/state layout) AND author the blank `docs/ui-guide.md`.
- pm-product-advocate (per-feature): run against the plan + contract + product.md + user-journeys; resolve any gaps with the PM (one AskUserQuestion pass).
- pm-coder: implement per plan (+ arch note + ui-guide). Additive only; never touch existing tests.
- Post-coding: pm-architect updates docs (ui-guide refresh, user-journeys, architecture decisions incl. reserved owner-marker seam + deps, threat-model rows + Last reviewed, README quick-start). Then review loop: pm-plan-checker Pass 1 → code-review Pass 2. Then regenerate product-map + append contract Built/changed-by. Then ship (pr-prep) on PM go.

## Next step

Run pm-architect (arch note + ui-guide) and pm-product-advocate; relay any advocate gaps to the PM; then hand off to pm-coder.

## Validation

Plan Test plan: JVM tests (`./gradlew test`, Robolectric in place) for invite codec / wiring / send / dedup; Compose UI tests via `createComposeRule` for the screens; `./gradlew ktlintCheck` + `./gradlew lint`. Live-camera QR decode is a manual on-device step (same class as connectedAndroidTest, decision 8). No new pipeline validator.

## Notes

GitHub remote: `git@github.com:aadegtyarev/open-webdav-messenger.git` (private). Current version: v0.9.0. This is the FIRST user-facing feature — all prior work was backend substrate. Bearer-invite is an accepted MVP limit (backlog). Owner-migration: design reserved only, not built. Public/passphrase chats, rich rendering, discovery UI: deferred to later slices.
