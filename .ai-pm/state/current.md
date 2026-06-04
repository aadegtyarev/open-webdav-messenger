# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

(none active)

## Status

idle

## Done

- chat-directory feature complete → released v0.7.0, PR #1 squash-merged to main (d358b42). Archived to archive/chat-directory-2026-06-04.md.
- release-ci feature complete: GitHub Actions CI — PR-check workflow (3 JVM gates per PR) + release workflow (version bump → debug APK + auto-tag + GitHub Release with APK, idempotent, one job). Stack-notes GitHub Actions section, architecture "Release flow" rewritten, pm-plan-checker approve + code-review passed (shell-injection-hygiene finding fixed). Released v0.8.0; PR #2 squash-merged (1107d16). LIVE-VALIDATED: pr-checks.yml green on the PR, release.yml green on merge → tag v0.8.0 + GitHub Release + app-debug.apk all present. Backfill tags v0.6.0/v0.7.0 pushed. Archived to archive/release-ci-2026-06-05.md.
- Flaky-test deflake (PR #4, e8772c1): CI caught `WebDavConcurrencyTest.cancellation_mid_retry_stops_further_attempts` racing on a `lateinit job` (UninitializedPropertyAccessException under CI load). Fixed test-only (LAZY coroutine start so `job` is assigned before the cancelling delayer reads it); 20/20 loop + CI green. Production code untouched.
- Protocol bump (PR #3, 55efbbf): ai-pm-protocol v2.23.0 → v2.25.1 (submodule e940085 → fc2faec). All additive, no migration; settings.json symlink makes the v2.25.1 hook change active automatically. CI green (with the deflake merged in first). PENDING per maintenance flow: run /pm-audit (the new v2.24–v2.25 content disciplines — state-model section, automode-procedural-gates, NFR-limits — are surfaced by audit docs-currency, none blocking).

## Remaining

- Next feature — PM's choice. Roadmap (`.ai-pm/backlog.md`): invite/onboarding (distributes the community key + chat keys; two-layer community vs chat membership) → rotation (rotate-with-auto-replace, member removal) → community (host-governed: meta/community.json owner marker, polling floor) → compression → UI; plus sync follow-ons (retention-window pruning, foreground-service fast-delivery, app-startup wiring) and local-DB-at-rest encryption (SC17/T16).
- Pending (offered, not yet backlogged): CI action-majors are on Node.js 20 (force-migrated to Node 24 on 2026-06-16, Node 20 removed 2026-09-16) — bump checkout/setup-java/setup-gradle/action-gh-release majors before then.

## Next step

Wait for PM: recommended /pm-audit after the protocol bump; else pick the next feature (or the Node-20 CI-action bump).

## Validation

release-ci: `./gradlew test ktlintCheck lint` green locally; CI live-green (pr-checks.yml 5m41s on PR #2; release.yml 4m20s on merge → v0.8.0 tag + Release + APK verified). chat-directory: JVM gates + connectedAndroidTest green on hardware. Decision 8 (automated CI device gate) still open — device tests remain a manual on-device run.

## Notes

Trivial fixup (2026-06-05, branch fix/flaky-webdav-cancellation-test): deflaked WebDavConcurrencyTest.cancellation_mid_retry_stops_further_attempts — coroutine now starts LAZY so `job` is assigned before the cancelling delayer can read it (was UninitializedPropertyAccessException under CI load). Test-only, no production change. 20/20 loop passes; full pipeline green.
GitHub remote configured: `git@github.com:aadegtyarev/open-webdav-messenger.git` (private). CI live: `.github/workflows/pr-checks.yml` (PR gates) + `release.yml` (auto-tag + Release + debug APK on version bump). Today 2026-06-05. Version baseline v0.8.0 (7 substrates + CI). Remote tags: v0.6.0, v0.7.0, v0.8.0. CHANGELOG.md current.
Done substrates: webdav-transport, crypto, identity, message-model, sync, directory, chat-directory; + release-ci (infra).
Release scope boundary: debug-signed APK only — release-signing / keystore-secret management deferred. connectedAndroidTest stays manual (decision 8 — no CI emulator).
Open decisions: #6 foreground polling side; #7 ktlint vs detekt; #8 CI emulator for connectedAndroidTest.
Known CI maintenance: action majors on Node.js 20 → bump before 2026-09-16 (Node 20 runner removal).
Next-feature roadmap deferrals (chat-directory): invite/onboarding key distribution; authoritative chat-ownership marker; chat-id grammar; UI; local Room cache; per-member revocation (rotation).
