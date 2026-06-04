# Execution state — ARCHIVED (release-ci, 2026-06-05)

Snapshot of the completed release-ci task. Archived on release; `current.md` reset to idle.

---

## Task

release-ci: GitHub Actions CI — (1) PR-check workflow (3 JVM gates on every PR); (2) release workflow (version bump on main → debug APK + auto-tag v<version> + GitHub Release with APK, idempotent, one job per the GITHUB_TOKEN no-retrigger rule); + one-time backfill of v0.6.0/v0.7.0 tags. Infra/CI, no app code. PM answers: PR-checks + auto-tag; tag + Release with debug APK.

## Status

done — released v0.8.0, PR #2 squash-merged to main (1107d16); CI validated live.

## Done

- Branch `feature/release-ci` cut from main. Two product forks ESCALATED + answered by PM: CI scope = PR-checks + auto-tag; release output = tag + GitHub Release with debug APK.
- pm-stack-researcher: authored the GitHub Actions section in `docs/stack-notes.md` (cited idioms; the GITHUB_TOKEN no-retrigger pitfall; no-submodule checkout; device tests off CI — decision 8).
- pm-coder: `.github/workflows/pr-checks.yml` (pull_request → checkout no-submodules + Temurin 17 + setup-gradle → `./gradlew test ktlintCheck lint`; contents: read) + `.github/workflows/release.yml` (push:main + workflow_dispatch → read versionName → tag-exists guard → assembleDebug → push annotated tag → softprops/action-gh-release@v2 with the APK; all one job; contents: write). CLAUDE.md one-line CI note. assembleDebug verified producing app/build/outputs/apk/debug/app-debug.apk locally; `./gradlew test ktlintCheck lint` green.
- pm-architect (post-coding): rewrote architecture.md "Release flow" (N/A → the two-workflow CI; debug-signing-only scope boundary; decision 8 cross-ref).
- Review loop: pm-plan-checker pass 1 (approve, DoD pass infra row, no blocking) → code-review pass 2 (1 finding — shell-injection hygiene: `${{ }}` interpolated into run: shell → bound via env: TAG, fixed in f57ce75; 1 edge case + 2 checks dropped) → stamped `## Code review: 2026-06-05 — passed`. actionlint unavailable in env (manual review, honestly recorded).
- Release: pm-pr-prep bumped v0.7.0→v0.8.0 (versionCode 7→8) + CHANGELOG, pushed, opened PR #2. PM authorized merge.
- LIVE CI VALIDATION: PR #2 triggered `pr-checks.yml` → GREEN (jvm-gates 5m41s on GitHub ubuntu-latest). Merged (squash, 1107d16) → `release.yml` GREEN (4m20s): built the debug APK in GitHub's cloud, pushed tag `v0.8.0`, published GitHub Release v0.8.0 (not draft) with `app-debug.apk` attached. End-to-end proof.
- Backfill: annotated tags `v0.7.0` (on d358b42) + `v0.6.0` (on b535ee4) created + pushed. Remote tags now: v0.6.0, v0.7.0, v0.8.0.

## Validation

`./gradlew test ktlintCheck lint` green locally. Live CI: pr-checks.yml GREEN on PR #2 (5m41s); release.yml GREEN on merge (4m20s) — tag v0.8.0 + Release + app-debug.apk all verified present on GitHub. Pass 1 approve + Pass 2 passed-stamp.

## Notes

- Known non-fatal CI annotation: actions/checkout@v4, setup-java@v4, setup-gradle@v4, action-gh-release@v2 run on Node.js 20, force-migrated to Node 24 on 2026-06-16, Node 20 removed 2026-09-16. Future maintenance: bump action majors (or set FORCE_JAVASCRIPT_ACTIONS_TO_NODE24). Not blocking. Offered to PM for backlog.
- Scope boundary (recorded): debug-signed APK only — release-signing / keystore-secret management deferred (future security-sensitive feature). connectedAndroidTest stays the manual device gate (decision 8 — CI emulator still open).
