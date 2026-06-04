# release-ci — plan

> GitHub Actions CI for the project, which just gained a private GitHub remote. Two workflows: (1) a **PR-check** workflow that runs the existing JVM pipeline (`test` + `ktlintCheck` + `lint`) on every pull request, so a regressing PR can't be merged green; (2) a **release** workflow that, when a version bump lands on `main`, builds the debug APK, creates and pushes the `v<version>` git tag, and publishes a GitHub Release with the APK attached. Plus a one-time backfill of the missing `v0.6.0` / `v0.7.0` tags. Infrastructure/CI — no application code, no user-facing behavior. Replaces the `docs/architecture.md` "Release flow: N/A" with a real, documented pipeline.

## Scenarios

System behaviors (CI infrastructure — the subjects are the workflow / a push / a release, not an end-user):

1. **PR check gate.** When a pull request targets `main`, a GitHub Actions workflow checks out the code (**without** submodules — `.ai-pm/tooling` is a private SSH submodule the build does not use), sets up Temurin JDK 17 + Gradle caching, and runs the three JVM gates `./gradlew test ktlintCheck lint`. A failing gate fails the check (red PR). The device-gated `connectedAndroidTest` is **not** run in CI (no emulator — decision 8; it stays a manual on-device step, documented).
2. **Release on version bump (auto-tag + Release + APK).** When a commit lands on `main` whose `versionName` in `app/build.gradle.kts` has no existing `v<version>` git tag, a release workflow builds the debug APK (`./gradlew assembleDebug`), then — **in one job** (a tag pushed with `GITHUB_TOKEN` does not retrigger a tag-listening workflow) — creates and pushes the `v<version>` tag and publishes a GitHub Release for it with `app-debug.apk` attached. **Idempotent:** if the tag already exists, the workflow no-ops (no double-tag, no duplicate Release).
3. **Manual release trigger.** The release workflow also accepts a manual `workflow_dispatch` run (so a maintainer can (re)publish the Release/APK for the current `main` version on demand — used for the v0.7.0 backfill Release).
4. **One-time tag backfill.** `main` is at v0.7.0 with no tag, and v0.6.0 was never tagged. The rollout creates the missing tags (`v0.7.0` on current `main`; `v0.6.0` on its merge commit) so the tag history is complete and the auto-tag idempotency check is consistent going forward.
5. **Non-version push no-ops.** A push to `main` that does not change `versionName` (e.g. a docs or chore commit) triggers the release workflow, which finds the tag already exists for the current version and does nothing — no spurious tag or Release.

## Existing behaviors this feature touches

No application behavior changes. Touch points are build/release infrastructure:

- **The Gradle pipeline** (`./gradlew test ktlintCheck lint`, `CLAUDE.md`): the PR-check workflow **invokes the same commands**, unchanged — it hosts the existing gates in CI, it does not redefine them. The device-gated `connectedAndroidTest` (decision 8) stays out of CI.
- **Versioning** (`app/build.gradle.kts` `versionName`/`versionCode`): the release workflow **reads** `versionName` to derive the tag; it does not change how the version is declared or bumped (that stays `pm-pr-prep`'s job on the feature branch).
- **The release flow** (`docs/architecture.md` "Release flow" — currently `N/A`): this feature is exactly the "release automation introduced" event that section anticipates; it must be rewritten to describe the CI (the architecture note says CI + that section are authored together).
- **The submodule** (`.ai-pm/tooling`, private SSH): the build does not reference it, so CI checks out **without** submodules — confirmed (no `settings.gradle.kts`/`build.gradle.kts` reference).

## Stack expectations touched

From `docs/stack-notes.md` → **GitHub Actions (CI — PR checks + release auto-tag)** (Last reviewed 2026-06-05):

- **Triggers:** `on: pull_request` for PR checks; `on: push: branches: [main]` (+ `workflow_dispatch`) for release. Source: <https://docs.github.com/en/actions/reference/workflow-syntax-for-github-actions>.
- **`actions/checkout@v4` — no submodules:** default `submodules: false`; this project's `.ai-pm/tooling` is a **private SSH** submodule the Gradle build does not use, so CI must **not** set `submodules: recursive` (fetch would fail). Source: <https://github.com/actions/checkout>.
- **`actions/setup-java@v4` Temurin 17 + `cache: gradle`** (matches the project's `jvmToolchain(17)`). Source: <https://github.com/actions/setup-java>. Plus `gradle/actions/setup-gradle@v4`. Source: <https://github.com/gradle/actions>.
- **Least-privilege `permissions:`** — `contents: write` is required to push a tag / create a Release; an omitted permission key defaults to `none`. Source: <https://docs.github.com/en/actions/concepts/security/github_token>.
- **Load-bearing pitfall — `GITHUB_TOKEN` does not retrigger:** a tag pushed with the built-in `GITHUB_TOKEN` does **not** start a `push: tags` workflow, so the auto-tag **and** the Release-with-APK must run in the **same job**. Source: <https://docs.github.com/en/actions/concepts/security/github_token>.
- **Release publishing:** `softprops/action-gh-release@v2` (`files:` = APK path) or `gh release create`; the APK is `app/build/outputs/apk/debug/app-debug.apk` from `./gradlew assembleDebug` (**debug-signed** — release-signing needs a keystore secret, out of scope). Source: <https://github.com/softprops/action-gh-release>.
- **Device tests off the CI fast path:** `connectedAndroidTest` needs `reactivecircus/android-emulator-runner` + KVM (heavy/slow) — the known CI limitation (decision 8); PR CI runs the 3 JVM gates only. Source: <https://github.com/ReactiveCircus/android-emulator-runner>.

## Interaction scenarios

Not provably isolated — the workflows react to the same `main` branch and PR events and share the version/tag state.

- **A merge to `main` is a `push`, a pre-merge check is a `pull_request`:** the PR-check workflow runs on the PR (before merge); the release workflow runs on the post-merge push. They never run on the same event; no double-run, no race on the same job.
- **A push to `main` that changes the version vs one that does not:** a version-bump push creates the new tag + Release; a non-version push finds the tag already present and no-ops (scenario 5).
- **A re-merge / re-push at an already-tagged version:** the tag-existence check makes the release workflow idempotent (no duplicate tag/Release).
- **Two PRs open concurrently:** each gets an independent PR-check run; neither blocks the other; both must be green to merge.
- **The submodule is absent in CI:** because the build does not reference `.ai-pm/tooling`, `./gradlew test ktlintCheck lint` and `assembleDebug` succeed without it (asserted by the no-submodule checkout actually building green — this PR's own CI run is the proof).

## Test plan

CI workflows are validated by **execution**, not unit tests — this feature's own PR and release are the live proof:

- **Existing tests that must pass:** all existing JVM suites stay green locally (`./gradlew test ktlintCheck lint`) — the workflows only invoke them, they don't change them.
- **Scenario 1 (PR check) — live validation:** opening THIS feature's PR triggers `pr-checks.yml`; it must complete **green** on the PR (the real proof the workflow checks out without the submodule and runs the three gates). Recorded in the PR.
- **Scenario 2 + 3 (release) — live validation:** merging THIS feature (which `pm-pr-prep` bumps to **v0.8.0**) triggers `release.yml` on the `main` push → it must build the APK, create the `v0.8.0` tag, and publish the v0.8.0 Release with `app-debug.apk` attached. This is the end-to-end proof of the auto-release path; verified by the orchestrator after merge (tag + Release exist, APK downloadable).
- **Scenario 4 (backfill):** after merge, `v0.6.0` and `v0.7.0` tags exist on their commits (one-time rollout step); verified by `git ls-remote --tags`.
- **Scenario 5 (no-op) — live validation:** the post-merge state-archive chore push (a non-version change) triggers `release.yml` and must no-op (no spurious tag); verified by the orchestrator.
- **Static check:** the workflow YAML is validated with `actionlint` locally before commit (offered optional in stack-notes; run once here as a cheap correctness gate — not added as a mandated pipeline validator).

## Docs to update

- **`docs/architecture.md`** — rewrite the **"Release flow"** section (currently `N/A — no automated release pipeline exists`) to describe the introduced CI: the PR-check workflow (the three JVM gates; device gate still manual per decision 8), the release workflow (version-bump → auto-tag + GitHub Release + debug APK; idempotent; one-job because of the `GITHUB_TOKEN` no-retrigger rule), the debug-signing scope boundary (release-signing/keystore deferred), and the new `.github/workflows/` files in the File-layout note. Owner `pm-architect`, post-coding handoff. (The architecture note explicitly says this section + CI are authored together.)
- **`docs/stack-notes.md`** — GitHub Actions component section + the optional `actionlint` validators-table row. **Already authored** by `pm-stack-researcher` (Last reviewed 2026-06-05).
- **`CLAUDE.md` Pipeline section** — a one-line note that the three JVM gates now also run in CI on every PR (the commands are unchanged; CI hosts them). `connectedAndroidTest` remains the manual device gate (decision 8). Minor, additive — keeps the single source honest that CI mirrors the local pipeline.

## Out of scope

- **Release-signed APK / AAB + keystore-secret management.** The release workflow ships the **debug-signed** APK only; production signing (a keystore secret in CI) is a separate, security-sensitive feature.
- **`connectedAndroidTest` in CI.** Running the device-gated instrumented tests on a CI emulator (`reactivecircus/android-emulator-runner` + KVM) is heavy/slow and is the open decision 8; deferred — the device gate stays a manual on-device run.
- **`actionlint` as a mandated pipeline validator.** Offered as optional in stack-notes and run once locally here; not wired into the required `CLAUDE.md` pipeline (keep CI light for a hobby project).
- **App-store / F-Droid publishing, changelog automation, dependency-update bots.** Separate later concerns.
- **Retroactive Release+APK for v0.6.0.** The backfill creates the **tags**; publishing a built APK Release for the already-shipped v0.6.0 is unnecessary (its APK can be built on demand). v0.7.0 gets a Release via the manual `workflow_dispatch` if desired; v0.8.0 onward is fully automatic.
