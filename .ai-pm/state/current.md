# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

idle

## Status

idle

## Done

- chat-directory feature complete → released v0.7.0, PR #1 squash-merged to main (d358b42). Archived to archive/chat-directory-2026-06-04.md.
- release-ci feature complete: GitHub Actions CI — PR-check workflow (3 JVM gates per PR) + release workflow (version bump → debug APK + auto-tag + GitHub Release with APK, idempotent, one job). Stack-notes GitHub Actions section, architecture "Release flow" rewritten, pm-plan-checker approve + code-review passed (shell-injection-hygiene finding fixed). Released v0.8.0; PR #2 squash-merged (1107d16). LIVE-VALIDATED: pr-checks.yml green on the PR, release.yml green on merge → tag v0.8.0 + GitHub Release + app-debug.apk all present. Backfill tags v0.6.0/v0.7.0 pushed. Archived to archive/release-ci-2026-06-05.md.
- Flaky-test deflake (PR #4, e8772c1): CI caught `WebDavConcurrencyTest.cancellation_mid_retry_stops_further_attempts` racing on a `lateinit job` (UninitializedPropertyAccessException under CI load). Fixed test-only (LAZY coroutine start so `job` is assigned before the cancelling delayer reads it); 20/20 loop + CI green. Production code untouched.
- Protocol bump (PR #3, 55efbbf): ai-pm-protocol v2.23.0 → v2.25.1 (submodule e940085 → fc2faec). All additive, no migration; settings.json symlink makes the v2.25.1 hook change active automatic. CI green (with the deflake merged in first).
- Post-bump audit (full scope, `.ai-pm/audits/audit-2026-06-05.md`): 0 blocking, 3 notes. All 8 features verified protocol-complete (5 earlier substrates clean, SC1–SC20 integral, no migration). Remediated (PR #5, aafb9e0): Note 1 — added `### System invariants` index to architecture.md; Note 2 — product-map intro count fixed. Note 3 — carried forward to future UI feature.
- Quality-sweep fixups (2026-06-05, PRs #6/#7/#8): 10 findings from full quality sweep; 9 fixed across 3 fixup PRs (finding #5 was false positive). Findings: IAE-crash → typed failure (publishEntry, publishChatEntry, lastSegment); dead-code guard fixed; Keystore wrap() now catches exceptions like unwrap(); OkHttp URL validation in gate(); PropfindParser.factory extracted to object-level; BigEndian helpers deduplicated from 2 codecs. Released v0.8.1/v0.8.2/v0.8.3.
- Protocol bump v2.25.1 → v2.36.0 (PR #9, 9d6b48d): ai-pm-protocol bumped. All additive, no migration. CI green.
- identity-store-io-dispatch (PR #9, bf8f12d): `IdentityStore.loadOrCreate()` made dispatcher-safe — `suspend fun` + `withContext(Dispatchers.IO)` + `kotlinx Mutex` replacing `synchronized`. `@WorkerThread` added to `load()`, `store()`, `has()`, `remove()`. Mutex non-reentrancy documented. Plan-checker: approve. Code review: 2 PLAUSIBLE findings fixed. Released v0.8.4.

## Remaining

- Next feature — PM's choice. Roadmap (`.ai-pm/backlog.md`): invite/onboarding (distributes the community key + chat keys; two-layer community vs chat membership) → rotation (rotate-with-auto-replace, member removal) → community (host-governed: meta/community.json owner marker, polling floor) → compression → UI; plus sync follow-ons (retention-window pruning, foreground-service fast-delivery, app-startup wiring) and local-DB-at-rest encryption (SC17/T16).
- Pending (offered, not yet backlogged): CI action-majors are on Node.js 20 (force-migrated to Node 24 on 2026-06-16, Node 20 removed 2026-09-16) — bump checkout/setup-java/setup-gradle/action-gh-release majors before then.

## Next step

Wait for PM: pick the next feature (roadmap → invite/onboarding), the Node-20 CI-action bump, or other.

## Validation

release-ci: `./gradlew test ktlintCheck lint` green locally; CI live-green (pr-checks.yml on PR #2; release.yml on merge → v0.8.0 tag + Release + APK verified). chat-directory: JVM gates + connectedAndroidTest green on hardware. identity-store-io-dispatch: JVM gates green; connectedAndroidTest is manual on-device gate (decision 8).

## Notes

GitHub remote: `git@github.com:aadegtyarev/open-webdav-messenger.git` (private). CI live: `.github/workflows/pr-checks.yml` + `release.yml`. Current version: v0.8.4. Remote tags: v0.6.0, v0.7.0, v0.8.0 (v0.8.1–v0.8.4 auto-tagged by release.yml on merge).
Done substrates: webdav-transport, crypto, identity, message-model, sync, directory, chat-directory; + release-ci (infra). identity-store-io-dispatch is a backend refactor, not a new substrate.
Release scope boundary: debug-signed APK only. connectedAndroidTest stays manual (decision 8).
Open decisions: #6 foreground polling side; #7 ktlint vs detekt; #8 CI emulator for connectedAndroidTest.
Known CI maintenance: action majors on Node.js 20 → bump before 2026-09-16 (Node 20 runner removal).
