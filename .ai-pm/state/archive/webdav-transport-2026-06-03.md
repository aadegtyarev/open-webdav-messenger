# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

webdav-transport — bootstrap the Android project + WebDAV transport layer + author docs/protocol/webdav-layout.md (the on-disk protocol spec). Backend-only, no UI/crypto.

## Status

coding — Pass-2 review fixes implemented, pipeline green, NOT committed (per task)

## Done

- Plan approved by PM, saved to docs/features/webdav-transport_plan.md
- Product map regenerated (feature in Infrastructure bucket)
- pm-architect: authored docs/protocol/webdav-layout.md + updated architecture.md
- pm-coder: bootstrapped Gradle Android project (single-module app/, Gradle wrapper 8.13,
  AGP 8.7.3, Kotlin 2.0.21, ktlint plugin 12.1.2, compileSdk/targetSdk 35, minSdk 26)
- pm-coder: implemented protocol primitives (Base32 lower + base32hex, message-id/content-hash,
  inbox-id derivation, order-token, envelope framing, chat-path layout) per webdav-layout.md §1–§5/§7
- pm-coder: implemented WebDAV transport (hand-rolled PROPFIND/MKCOL/GET/PUT/DELETE over OkHttp,
  single shared OkHttpClient, Depth:1, If-Match conditional PUT, typed 412/429 results,
  exponential back-off with injectable Delayer, Dispatchers.IO, response bodies always closed,
  reader integrity check → NotReady on mismatch)
- pm-coder: all Test-plan tests implemented + green (8 unit, 4 interaction, 4 stack-spec,
  8 protocol-primitive); full pipeline `./gradlew test lint ktlintCheck` GREEN
- pm-coder: portability fix — removed machine-specific `org.gradle.java.home` from
  gradle.properties; switched to the Gradle Java toolchain (`kotlin { jvmToolchain(17) }` in
  app/build.gradle.kts + `foojay-resolver-convention` 0.9.0 in settings.gradle.kts). Gradle
  auto-detects the local JDK 17 for compile tasks; the daemon/launcher run on the default JRE-21.
  All three pipeline commands GREEN with no JAVA_HOME set and no committed path. Committed build
  files are now path-free / CI-portable. NOT committed (per task).
- pm-coder (Pass-2 review fixes): addressed all 12 review findings — security (bounded GET read
  with 1 MiB cap → CleartextRejected/InvalidPath/Oversize-as-NotReady typed results; https-scheme
  enforcement with loopback carve-out for the MockWebServer harness; ../ path-traversal rejection;
  ConnectionConfig.toString() password redaction; manifest usesCleartextTraffic=false), WebDAV
  correctness (PROPFIND selects props from the 200 OK propstat block only; GET 404 → NotReady;
  CallExecutor cooperative cancellation via ensureActive + CancellationException rethrow; codec-id
  validated on Envelope.read per §7; PROPFIND parsed from raw bytes to avoid charset double-decode),
  robustness (transient 5xx 500/502/503/504 retried with the same back-off → TransportError on
  exhaustion), cleanup (removed the unreachable-code tail in CallExecutor; fixed the stale "30 chars"
  OrderToken KDoc to 29/62). Added 12 new tests (1 protocol, 11 transport) in the existing test
  files. New source file: transport/PathSafety.kt. Full pipeline `test`/`lint`/`ktlintCheck` GREEN.
  NOT committed (per task).

## Remaining

- pm-plan-checker: verify plan compliance + review-fix compliance (and resolve the order-token
  length spec defect below — the KDoc/test now state 29/62; docs/protocol still owned by pm-architect)
- Commit the work (pm-coder did NOT commit per task instruction)
- Manual smoke vs real Yandex.Disk (needs PM test account)
- Out of scope here, future features: crypto, compression, UI, sync loop, foreground-service mode

## Touched files

Pass-2 review fixes touched (uncommitted):
- app/src/main/AndroidManifest.xml (usesCleartextTraffic=false)
- app/src/main/kotlin/org/openwebdav/messenger/protocol/{Envelope,OrderToken}.kt
- app/src/main/kotlin/org/openwebdav/messenger/transport/{ConnectionConfig,WebDavResult,CallExecutor,
  PropfindParser,WebDavTransport}.kt
- NEW app/src/main/kotlin/org/openwebdav/messenger/transport/PathSafety.kt
- app/src/test/kotlin/org/openwebdav/messenger/protocol/ProtocolPrimitivesTest.kt
- app/src/test/kotlin/org/openwebdav/messenger/transport/{WebDavVerbTest,WebDavInteractionTest,
  WebDavConcurrencyTest}.kt

New (uncommitted, untracked):
- settings.gradle.kts, build.gradle.kts, gradle.properties, .gitignore, local.properties (git-ignored)
- gradle/libs.versions.toml, gradle/wrapper/*, gradlew, gradlew.bat
- app/build.gradle.kts, app/proguard-rules.pro, app/src/main/AndroidManifest.xml,
  app/src/main/res/values/strings.xml
- app/src/main/kotlin/org/openwebdav/messenger/OpenWebDavMessengerApp.kt
- app/src/main/kotlin/org/openwebdav/messenger/protocol/{Base32,MessageId,OrderToken,Envelope,ChatPaths}.kt
- app/src/main/kotlin/org/openwebdav/messenger/transport/{ConnectionConfig,WebDavResult,BackOff,
  PropfindParser,WebDavRequests,CallExecutor,WebDavTransport,TransportFactory}.kt
- app/src/test/kotlin/org/openwebdav/messenger/protocol/ProtocolPrimitivesTest.kt
- app/src/test/kotlin/org/openwebdav/messenger/transport/{TestSupport,WebDavVerbTest,
  WebDavConcurrencyTest,WebDavInteractionTest,StackSpecTest}.kt

## Next step

review — spawn pm-plan-checker to verify plan + Pass-2-review-fix compliance, then commit. Flag
for pm-architect: the order-token length in docs/protocol/webdav-layout.md is now internally
consistent at the field level (§2/§4 already say 29/62 in the spec body; the code/KDoc/test match).

## Validation

./gradlew test ; ./gradlew lint ; ./gradlew ktlintCheck (pipeline established by this feature). Stack-spec + interaction tests per the plan's Test plan.

## Notes

First feature — also bootstraps the whole Android project, so larger than later features. Topology A, content-addressed append-only message files. Providers: generic WebDAV + Yandex.Disk (Nextcloud deferred). Decisions resolved here: #6 ktlint+Lint (no detekt). Out of scope: crypto, compression, UI, sync loop, foreground-service mode.

Coder decisions / env notes:
- Hand-rolled PROPFIND/PUT over OkHttp (did NOT use sardine-android) — full control over verbs,
  Depth, If-Match, 429 back-off; PROPFIND XML parsed via javax.xml.parsers DOM (works on JVM for
  MockWebServer + on Android), XXE-hardened (DTD/external entities disabled).
- Gradle wrapper 8.13, AGP 8.7.3, Kotlin 2.0.21, ktlint-gradle 12.1.2, compileSdk/targetSdk 35,
  minSdk 26, JavaVersion 17 / jvmTarget 17.
- ENV: the system default `java` (java-21-openjdk-amd64) is a JRE with no `javac`. RESOLVED via the
  Gradle Java toolchain: `kotlin { jvmToolchain(17) }` (app/build.gradle.kts) routes Kotlin/javac
  compile tasks to an auto-detected JDK 17, while the Gradle daemon/launcher happily run on the
  JRE-21 (the daemon itself needs no compiler). `foojay-resolver-convention` 0.9.0
  (settings.gradle.kts) lets a machine/CI without a local JDK 17 auto-provision one. No
  machine-specific path is committed. On this box no JAVA_HOME is even required; on a host where
  Gradle cannot launch on a JRE, the portable fallback is to set `JAVA_HOME` to any JDK in the
  environment (not committed). README/CLAUDE.md dev-setup note: prefer the toolchain; if the Gradle
  daemon refuses to start on a JRE-only default `java`, run with
  `JAVA_HOME=/path/to/a/jdk ./gradlew …`.

SPEC DEFECT to resolve (pm-architect owns docs/protocol/webdav-layout.md — coder must not edit docs):
- The order-token length is internally inconsistent. §2 (line 83) and §4 (line 114) state the
  order-token is "30 chars" and §2 line 90 states message-id total "30 + 1 + 32 = 63". But the §4
  fixed field widths sum to 29: ts-millis(11) + "-" + sender-tag(8) + "-" + seq(8) = 29, so the
  message-id is 62. Code implements the PRECISE field widths (29/62) — the load-bearing definitions —
  and flags the "30"/"63" summary annotations as the wrong numbers. pm-architect should reconcile:
  either correct the summary to 29/62, or widen a field (e.g. ts-millis to 12) to make the total 30.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` and reset this one to a new task or to "Status: idle".
