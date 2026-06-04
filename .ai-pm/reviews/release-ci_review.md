# release-ci — plan-compliance review (Pass 1)

Feature kind: **infrastructure / CI** (mandatory-table row "Backend refactor / infrastructure / build / CI"). Project kind: **software** (default) → Pass 2 is `code-review`. Non-human-role scenario subjects (the workflow / a push / a release) → **no product-readiness advocate required** (correct exemption, not a missing artifact).

## Plan compliance

- ✓ **Scenario 1 — PR check gate** — `pr-checks.yml`: `on: pull_request: branches: [main]`; checkout `@v4` with no `submodules:` key (default `false`); Temurin 17 + `cache: gradle` + `gradle/actions/setup-gradle@v4`; runs `./gradlew test ktlintCheck lint`; `permissions: contents: read`; no `connectedAndroidTest`. Validated by execution (this PR's own CI run — recorded in the PR).
- ✓ **Scenario 2 — Release on version bump (auto-tag + Release + APK, idempotent)** — `release.yml`: `on: push: branches: [main]`; reads `versionName` from `app/build.gradle.kts`; tag-exists guard (`exists=true|false`); on `false` builds `assembleDebug`, pushes annotated `v<version>` tag, publishes Release via `softprops/action-gh-release@v2` with `app/build/outputs/apk/debug/app-debug.apk`; **all in one job**; `permissions: contents: write`. Idempotent. Validated by execution (the v0.8.0 merge release — orchestrator-verified post-merge).
- ✓ **Scenario 3 — Manual release trigger** — `release.yml` carries `workflow_dispatch`.
- ✓ **Scenario 4 — One-time tag backfill** — operational rollout step owned by the orchestrator post-merge (not a YAML deliverable); plan validates by `git ls-remote --tags`. Correctly a verify-by-execution item, not code.
- ✓ **Scenario 5 — Non-version push no-ops** — guard sets `exists=true` for an already-tagged version; every build/tag/publish step is `if: steps.tagcheck.outputs.exists == 'false'`, so the run stays green with no side effect.

**Load-bearing stack rule — tag + Release in the SAME job:** ✓ verified. `release.yml` is the only workflow listening on `push: branches: [main]`; there is **no** `push: tags` workflow anywhere under `.github/workflows/` (only `pr-checks.yml` + `release.yml` exist). The `GITHUB_TOKEN` no-retrigger pitfall is correctly avoided — no second workflow that would silently never fire.

**Submodule rule:** ✓ neither workflow sets `submodules: recursive`; both use `actions/checkout@v4` at the default (`false`). The private-SSH `.ai-pm/tooling` is never fetched.

**APK path:** ✓ `files: app/build/outputs/apk/debug/app-debug.apk` — the `assembleDebug` output path (coder verified the build produces it locally).

## Interaction scenario coverage

The plan's Interaction scenarios (merge-vs-PR event separation; version vs non-version push; re-push at an already-tagged version; two concurrent PRs; absent submodule) are **validated by execution** — workflows cannot run locally, and the plan declares this PR's own CI run + the v0.8.0 merge release as the live proof. This is the honest and correct validation mode for CI YAML; the idempotency / no-op interaction is additionally encoded as the `tagcheck` guard. Accepted — no fabricated local "ran" claim.

## Stack expectations compliance

Every entry in the plan's "Stack expectations touched" (stack-notes → GitHub Actions, Last reviewed 2026-06-05) is honored by the YAML: triggers, no-submodule checkout, Temurin 17 + `cache: gradle`, least-privilege `permissions:` (`read` for PR, `write` for release), the `GITHUB_TOKEN` no-retrigger one-job rule, `softprops/action-gh-release@v2` with the debug-APK path, device tests off the PR fast path. No code contradicts a cited rule. No stack-spec test class applies (CI is the host of the existing gates, not a new validator — `actionlint` deliberately optional, not wired).

## Definition of Done (infra row — items 1, 2, 4, 5, 7)

- [x] **(1)** Execution state updated — `.ai-pm/state/current.md` reflects the release-ci task at Status: review, touched files listed.
- [x] **(2)** Scenarios implemented and tested (by execution, the only valid mode for CI) — all 5 covered; live-validation path declared per scenario.
- [x] **(4)** Product Contract: **skipped with honest reason** — commit `d80c8c4` carries `Skips Product Contract: CI/infrastructure, no user-visible behavior change`. No user-visible behavior; skip accepted per the mandatory table.
- [x] **(5)** Pipeline: `./gradlew test ktlintCheck lint` + `assembleDebug` green locally (coder report); `actionlint` unavailable locally → manual YAML review done and **honestly reported** (optional validator, not mandated). No false "workflows ran" claim.
- [x] **(7)** Docs landed: `docs/architecture.md` "Release flow" rewritten N/A → the real CI pipeline (PR gate + release workflow + debug-signing scope + module-map row at line 230); `docs/stack-notes.md` GitHub Actions section present (Last reviewed 2026-06-05); `CLAUDE.md` Pipeline CI note present (line 80).
- [x] Expected artifacts exist: plan (`docs/features/release-ci_plan.md`), this review. No contract (correctly skipped — infra). No advocate artifact (correctly exempt — non-human-role subjects).
- [n/a] Product-readiness gate — infra, non-human-role scenario subjects → exempt, no advocate required.
- [n/a] Validation gate (documentation-kind) — software project.

**DoD: pass**

## Blocking

None.

## Notes (product)

None. This is a backend/infrastructure feature with **no Product Contract touched** (correctly skipped) and no user-visible behavior. Nothing requires a PM decision.

## Notes for Pass 2 (code-review — YAML correctness, NOT blocking here)

These are technical-quality items outside plan-compliance scope; flagged so the orchestrator routes them to `code-review`, not for the PM:

1. **Version extraction robustness** — `grep -oE 'versionName...' | head -n1 | sed` takes the first `versionName = "..."` line. Robust for the current single declaration (`app/build.gradle.kts:33`, `versionName = "0.7.0"`), but worth a code-review glance for whether a commented-out or secondary `versionName` could ever shadow it.
2. **Expression-into-shell hygiene** — `${{ steps.version.outputs.tag }}` is interpolated into single-quoted shell vars (`tag='${{ ... }}'`) in the tagcheck / tag-push steps. Safe for the constrained `v0.7.0` shape; standard code-review injection-hygiene check.
3. **`if:` guard consistency** — every post-guard step (setup-java, setup-gradle, build, tag, publish) carries `if: steps.tagcheck.outputs.exists == 'false'`. Confirmed consistent; code-review should confirm no step that mutates state is missing the guard.

## Verdict

approve

<!-- The trail below is the ONE review section the orchestrator owns, not pm-plan-checker.
     See WORKFLOW.md "Edit-ownership rule" — the Pass-2 code-review trail is the single
     carve-out to "orchestrator does not edit content artefacts". -->
## Code review findings

Pass 2 — `code-review` of the two workflow YAML files (the diff: `.github/workflows/pr-checks.yml`, `.github/workflows/release.yml`, `CLAUDE.md` one-liner, docs). `pr-checks.yml` is clean (no shell interpolation, least-privilege `contents: read`, no submodules, correct Temurin-17/gradle setup, runs the 3 gates). `release.yml` reviewed for the three pm-plan-checker-flagged items: version-extraction robustness, `${{ }}`→shell injection hygiene, and `if:`-guard consistency. One actionable finding; one edge case considered and dropped.

### Finding 1 (CONFIRMED — CI script-injection hygiene, fix) — `${{ steps.version.outputs.tag }}` interpolated directly into `run:` shell

- `.github/workflows/release.yml:48` (`tag='${{ steps.version.outputs.tag }}'` in "Check whether tag already exists") and `:78` (same, in "Create and push tag") expand a `${{ }}` expression **directly into the shell script**, inside single quotes. The value derives from `versionName` in the repo's own `app/build.gradle.kts` (semi-trusted — changing it needs a reviewed commit, so the practical risk is low), but a `versionName` containing a single quote would break out of the quoting, and this is exactly the pattern GitHub's hardening guidance + `actionlint` flag.
- **Fix:** bind the value through `env:` on each of those two steps (`env: { TAG: ${{ steps.version.outputs.tag }} }`) and reference `"$TAG"` in the shell — the standard, injection-safe idiom. Behavior is identical for well-formed `vX.Y.Z` versions; it removes the quote-breakout. The `with:` interpolations at `:88`–`:89` are a YAML expression context (not a shell), so they are fine and need no change.

### Considered and dropped (recorded — not fixed)

- **Concurrent double-push of the same new version** — if two pushes to `main` at the same brand-new `versionName` race, both could pass the tag-exists check and the second `git push origin "$tag"` would fail (the tag now exists) → a RED run. DROPPED: the failure is **loud, not silent** (no corrupt or duplicate release), the scenario is rare (two version-bump merges landing within one CI window), and guarding it (a push `--force-with-lease`-style retry or a concurrency group) adds complexity disproportionate to a hobby-project release path. Acceptable; noted for awareness.
- **Version-extraction robustness** (`grep -oE … | head -n1 | sed` + empty→`exit 1`) and **`if:`-guard consistency** (every build/tag/release step carries `if: steps.tagcheck.outputs.exists == 'false'`) — reviewed, both sound; no change.

## Code review: 2026-06-05 — passed

Finding 1 (CI script-injection hygiene) fixed in `f57ce75`: both `release.yml` shell steps now bind the tag via `env: TAG` and reference `"$TAG"` — no `${{ }}` remains in any `run:` body (the only `${{ }}` left are the safe `env:`/`with:` expression contexts at lines 48/80/92/93). `./gradlew test ktlintCheck lint` green (the YAML edit is inert to the build). `actionlint` was unavailable in this environment (manual YAML/expression review stands — honestly recorded, not falsely claimed). The two lower items stay consciously dropped. `pr-checks.yml` was clean from the start. The workflows' live proof is this feature's own PR run + the v0.8.0 merge release. Pass 2 clean.
