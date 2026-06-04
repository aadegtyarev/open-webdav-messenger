# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

release-ci: GitHub Actions CI — (1) PR-check workflow running the 3 JVM gates (test/ktlintCheck/lint) on every PR; (2) release workflow that on a version bump to main builds the debug APK, auto-tags v<version>, and publishes a GitHub Release with the APK (idempotent, one-job per the GITHUB_TOKEN no-retrigger rule); + one-time backfill of v0.6.0/v0.7.0 tags. Infra/CI, no app code. PM answers: PR-checks + auto-tag; tag + Release with debug APK.

## Status

review

## Done

- Template bump: ai-pm-protocol v2.13.0 → v2.22.0 → v2.23.0 merged to main; no pending migrations; decision-authority project default stays interactive.
- chat-directory feature complete: plan (group-only chat directory; DMs excluded — escalated PM scope decision; autonomous per-feature authority) → pre-coding arch note (thin `chatdirectory/` package on §10 primitives; supersede by chat-id; no cache) → coder (`app/.../chatdirectory/` 8 files + webdav-layout §11 + SupersedeResolver generalized; 26 JVM + 2 instrumented tests) → post-coding docs (architecture decision 13 + SC18/SC19 generalized + SC20; threat-model T23-T25) → pm-plan-checker pass 1 (approve) → code-review pass 2 (1 reuse finding fixed, 3 dropped; passed) → device gate GREEN on Xiaomi M2102J20SG (24 instrumented tests, 0 failures) → released v0.7.0 + CHANGELOG → PR #1 squash-merged to main (d358b42). Archived to archive/chat-directory-2026-06-04.md.

## Remaining

- post-coding docs handoff: pm-architect rewrites architecture.md "Release flow" (N/A → the CI pipeline).
- review loop: pm-plan-checker pass 1 → code-review pass 2 → stamp.
- ship: pm-pr-prep bump to v0.8.0 + CHANGELOG + PR; PM authorizes; merge → release.yml auto-creates v0.8.0 tag + Release + APK (live validation); orchestrator backfills v0.6.0/v0.7.0 tags.
- After: next feature — roadmap (`.ai-pm/backlog.md`): invite/onboarding → rotation → community → compression → UI; plus sync follow-ons and local-DB-at-rest encryption (SC17/T16).

## Next step

Review loop: pm-architect docs handoff (architecture.md "Release flow"), then pm-plan-checker pass 1 → code-review pass 2.

## Touched files

- `.github/workflows/pr-checks.yml` (new) — PR-check workflow: checkout (no submodules) + Temurin 17 + setup-gradle + `./gradlew test ktlintCheck lint`; `permissions: contents: read`.
- `.github/workflows/release.yml` (new) — release workflow: push:main + workflow_dispatch; `permissions: contents: write`; read versionName → tag-exists guard → (if new) assembleDebug + annotated tag push + softprops/action-gh-release@v2 with the debug APK; all in one job.
- `CLAUDE.md` — one additive line in Pipeline noting the 3 JVM gates run in CI per PR; connectedAndroidTest stays the manual device gate.

## Validation

chat-directory: three JVM gates green (full suite incl. 26 chat-directory + all §10 directory tests) + connectedAndroidTest GREEN on a real device this run (24 instrumented tests, 0 failures — native AEAD seal/open + Ed25519 sign/verify + Keystore). Decision 8 (CI emulator as the *automated* gate) still open; the manual device run is green.

## Notes

GitHub remote now configured: `git@github.com:aadegtyarev/open-webdav-messenger.git` (private). `main` pushed; PRs flow via `gh` (pm-pr-prep). Today 2026-06-04. Version baseline now v0.7.0 (7 substrates), CHANGELOG.md current.
Done substrates: webdav-transport, crypto, identity, message-model, sync, directory, chat-directory.
No git tags for v0.6.0 / v0.7.0 — project has no auto-tag CI (`.github/workflows/` absent; architecture.md "Release flow: N/A"). Stale `v0.5.0` tag exists. Tagging/CI is a deferred decision (offered to PM).
Open: decision #6 foreground side; decision #7 (ktlint vs detekt); decision #8 (CI emulator for connectedAndroidTest — 7 substrates have device-gated tests; chat-directory's ran green on hardware this release).
Chat-directory deferred (backlog / out of scope): invite/onboarding key distribution (community key + private-chat keys); DMs in directory (excluded — social-graph privacy); authoritative chat-ownership marker / host-attested chat entries; chat-id grammar pinning; UI (chat-list surface, join button); local Room cache of chat-directory entries (UI feature); per-member revocation / re-key (rotation feature).
