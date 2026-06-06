# ui-chat-surface — design notes

## Context

This is the project's **first user-facing feature**. Every substrate to date (transport, crypto, identity, message-model, sync, directory, chat-directory) is backend-only and exercised by tests alone — there is no `app/.../ui/` package, no Compose, the `Application` is an intentionally-empty host (`OpenWebDavMessengerApp`), and `SyncWorker` runs the **default no-op `SyncRunner`** (a benign clean cycle before any config exists).

This feature turns the headless engine into a usable app along one thin vertical path with two UI roles (owner / member — both end with the same device-local state: connection config + chat key + identity; only the *arrival* of that state differs). The structural choices are real because the plan adds **the runtime-config + app-startup-wiring + invite-codec + secure-config-store seams the backend substrates deliberately deferred** ("the future config/UI feature"), plus a new Compose layer that must compose every layer below without breaking the existing dependency arrows. None of the substrate behavior changes — the UI **consumes** the engine seams unchanged (plan "Existing behaviors this feature touches"; contract `community-chat.md` "Must not break").

Six structural choices are mapped below, each with options + a recommendation:
1. App-startup wiring home (engine composition + `SyncRunner.install` / `SyncScheduler.schedule`) — and where the user-driven SEND path gets its composed `SyncEngine`.
2. Where the `owdm1:` invite-token codec lives.
3. Secure at-rest storage of the WebDAV connection config + app-password.
4. The owner/member role model + the Compose state/ViewModel layout.
5. The QR scanner integration (`zxing-android-embedded` in `AndroidView` + CAMERA flow + paste fallback).
6. Reserve (design-only) the community-owner-marker seam.

Plus a dependency-arrow rule that constrains all six (last section).

## Adjacent implementations

The codebase already has a consistent **factory + seam** pattern for every substrate; the UI must follow it rather than reach inside.

1. **`crypto/CryptoFactory`** at `app/.../crypto/CryptoFactory.kt` — owns the single `LazySodiumAndroid`/`NativeCrypto` for the process; exposes `aead()`, `keySources()`, `messageCrypto()`, `chatKeyStore(context)`. The doc says "construct one per process and share it." This is the canonical "compose the native-backed substrate once" home.
2. **`identity/IdentityFactory`** at `app/.../identity/IdentityFactory.kt` — mirrors `CryptoFactory`: one `LazySodiumAndroid`, exposes `identityCrypto()` and `identityStore(context)`. Note: **two factories each construct their own `LazySodiumAndroid`** today — a wiring home composing both should be aware it currently means two native bindings (acceptable; or a future consolidation, not this slice's job).
3. **`transport/TransportFactory`** at `app/.../transport/TransportFactory.kt` — `internal object`; owns the single shared `OkHttpClient`; `create(config: ConnectionConfig)` builds a `WebDavTransport`. The `ConnectionConfig` it takes is `internal`, so anything composing transport is either in the `transport` package or in `sync`/a new package with `internal` visibility into the module (single Gradle module — all packages share `internal`).
4. **`sync/SyncRunner`** at `app/.../sync/SyncRunner.kt` — the **poll-only** seam: a `fun interface` with a process-global `@Volatile installed` runner, `install(runner)` / `current()`. `SyncWorker.doWork()` calls `SyncRunner.current().runOnce()`. The KDoc explicitly says a real implementation "builds the `SyncEngine` from the stored connection config + roster and the `ChatKeyStore`, then calls `SyncEngine.pollCycle`."
5. **`sync/SyncEngine`** at `app/.../sync/SyncEngine.kt` — `internal class`; `send(chatId, orderToken, envelopeBytes, allMembers, senderIdentifier)` and `pollCycle(memberIdentifier, subscriptions)`. Composed from `WebDavTransport` + `MessageEnvelope` + `MessageStore` + `ChatKeyProvider`. **This is the shared object both the poll path and the send path need.**
6. **`sync/SyncScheduler`** at `app/.../sync/SyncScheduler.kt` — `object`; `schedule(workManager, requestedMinutes = 15)` enqueues the unique periodic poll, clamped to the 15-min floor. Idempotent (`ExistingPeriodicWorkPolicy.UPDATE`).
7. **`keystore/ChatKeyStore`** + **`keystore/ChatKeyStorePort`** at `app/.../keystore/` — Keystore-wrapped per-chat key store; the narrow `store/load` port already exists. **`keystore/KeystoreWrapper`** is the shared `iv ‖ ct+tag` atomic-write + typed-unwrap mechanism, parameterised by `(alias, file)` — already reused by both `ChatKeyStore` (`owdm.chatkey.wrap.v1`) and `IdentityStore` (`owdm.identity.wrap.v1`). This is the exact precedent for the new connection-config store.
8. **`MessageEnvelope.seal(message, chatKey, senderSignSecret)`** at `app/.../message/MessageEnvelope.kt` — the build seam the send path calls: `TextMessage → signAndSerialize → AEAD-seal → contentName`. `MessageEnvelope.create(messageCrypto, identityCrypto)` is the factory.

## Behavioral risks in this area

The UI shares Room + the engine seams with the background `SyncWorker` — they are **not isolated** (plan "Interaction scenarios"). The event/subscription map that matters:

- **Feed observes Room (`MessageStore.observeChat(chatId)` → `Flow`).** Both the foreground send (immediate local echo via `MessageStore.persist`) **and** the background poll (`PollReader` → `MessageStore.persist`) write the same Room table. Room's `Flow` is the single source of truth the feed renders — there is **no separate UI message list to keep in sync**. This is the load-bearing design that makes "a poll lands while the feed is open → appears on its own" free, and makes "send echo + later poll re-fetch → one row" free (dedup by §2 message-id PK in `MessageStore`/`MessageDao.insertIgnore`).
- **Feedback-loop check: clean.** The only mutation that can feed back into a subscription is `MessageStore.persist`, and it is **idempotent on the message-id** (the §2 content-addressed name). The send path persists the local echo under the **same** id the poll will later see, so the re-fetch is a no-op insert — no duplicate row, no recompose storm. The UI must **not** add a second persistence path or a second observable; it must read the existing `Flow` only (test `feed_consumes_flow_not_blocking_query`).
- **The no-op runner must survive.** A scheduled poll before connect/join hits `SyncRunner.current()` = the default no-op → `CycleOutcome(0,0,backedOff=false)` → `Result.success`. The wiring must only `install(...)` a real runner **once a config + chat exist**; installing too early (before config) would build an engine over a null config. Keep the no-op until config is present.
- **Send while a poll cycle runs.** `SendWriter` writes one content-addressed `log/` file + (M−1) change-notes, all non-conditional new files; `PollReader` lists `log/` and dedups. Roster is `[self]` this slice, so `send` writes only the shared-log copy (no change-notes — `allMembers=[self]` ⇒ `others` is empty) and the immediate local echo is the user's own row. No collision, no feedback loop (the engine guarantees are preserved verbatim).

## The six structural choices

### Choice 1 — App-startup wiring home (+ the send path's engine access)

**The problem.** Two runtime paths need the *same* composed `SyncEngine`:
- the **poll path** — `SyncWorker` → `SyncRunner.current().runOnce()`, which must build/hold a real engine and call `pollCycle`;
- the **send path** — the chat screen's ViewModel calls `MessageEnvelope.seal(...)` then `SyncEngine.send(...)` then persists the echo. **`SyncRunner` is poll-only** (it exposes only `runOnce()`), so the send path needs its **own** handle to a composed `SyncEngine` — it cannot get one through `SyncRunner`.

Both must be built from the **same** stored connection config + identity + chat key, and config may not exist yet (keep the no-op runner until it does).

- **Option A — a dedicated wiring object (`ui/EngineWiring` or `app/AppContainer`), called from `Application.onCreate()` (RECOMMENDED).** A single app-owned composition root that: (1) on launch reads the persisted connection config + the joined chat from the secure store (Choice 3); (2) if present, builds the `SyncEngine` (transport via `TransportFactory.create(config)`, `MessageEnvelope.create(...)`, `MessageStore`, a `ChatKeyProvider` wrapping `ChatKeyStore.load`), installs a real `SyncRunner` that closes over `engine.pollCycle(self, subscriptions)`, and calls `SyncScheduler.schedule(workManager)`; (3) if absent, leaves the no-op runner. The **same** built `SyncEngine` (and the rebuild-on-config-change entry point) is exposed to ViewModels for the send path — one composition, two consumers. `Application.onCreate` calls into this object; the object holds the process-scoped graph. This matches the existing factory pattern and the `SyncRunner` KDoc's stated intent ("set once at app start via `install`").
  - Pros: single composition root; the send path and poll path provably share one engine (the plan's `relaunch_with_saved_config_reinstalls_runner` test wants exactly this — "drive the same `SyncRunner.install` path the production `Application` uses"); keeps `OpenWebDavMessengerApp` thin (it delegates, holds no logic); testable without WorkManager (the wiring object is plain Kotlin).
  - Cons: a process-scoped mutable graph that must be rebuilt when config first lands (member just joined / owner just created) — needs a single `reconfigure(config)` entry the onboarding ViewModels call after persisting, which re-`install`s the runner + re-`schedule`s. One careful seam, but explicit.
  - Risks: if the send path and the installed runner build **two different** engines, the test-wiring-parity intent breaks; the wiring object must expose the one engine, not let each caller re-compose.
- **Option B — compose inside `MainActivity` (a `MainActivity`-driven init).** Build the engine when the first screen appears.
  - Pros: nothing runs before there is a UI.
  - Cons: **wrong lifecycle.** The poll runs via `SyncWorker` in the background with no Activity alive — the runner must be installed at *process* start, not at Activity start, or a background poll right after process death (with config saved) hits the no-op runner and silently does nothing until the user opens the app (violates "background catch-up while closed" + `relaunch_with_saved_config_reinstalls_runner`). Rejected.
- **Option C — fold wiring into `OpenWebDavMessengerApp` directly (no separate object).** Put the composition in `Application.onCreate()` itself.
  - Pros: one fewer file.
  - Cons: the `Application` becomes a hard-to-test god object (it is an Android framework type — awkward under `./gradlew test`); the send-path engine handle has to hang off the `Application`, which ViewModels then reach through `getApplication()`. The wiring object (Option A) is the same code, testable, with the `Application` as a one-line delegator. Mildly worse, not forced.

**Recommendation: Option A** — a dedicated, testable wiring object as the single composition root, invoked from `Application.onCreate()`, exposing both the installed poll runner and the shared `SyncEngine` for the send path, with a `reconfigure(config)` entry the onboarding flow calls after first persisting config. This satisfies the `relaunch_with_saved_config_reinstalls_runner` test-wiring-parity requirement directly and keeps the no-op runner until config exists.

**Note for the coder (not a plan change):** `SyncEngine` is `internal` and `ConnectionConfig` is `internal` — the wiring object lives **inside the same Gradle module** (`app/`), so place it in a package with `internal` reach (e.g. `app/.../ui/` or a new `app/.../app/` wiring package). It can construct `SyncEngine` directly. The `senderIdentifier` / `memberIdentifier` passed to the engine is the member's stable identity — derive it from the loaded `Identity` (e.g. its sign-public key encoded), consistent with how `OrderToken.build` and `member-index-id` already key on a member identifier string.

### Choice 2 — Home of the `owdm1:` invite-token codec

**The problem.** A new app-owned codec: `owdm1:<base64url(gzip(json))>` carrying disk access (URL + username + app-password + chat-root) + chat-id + the raw random chat key + community name. Plain encoding, **not encryption** — a bearer token. Decode is reject-don't-guess (unknown prefix / bad base64url / bad gzip / missing field = typed rejection). It is **app-owned, not crypto** (the architecture is explicit: the invite token "travels out-of-band, never written to disk, never to an external system — it is not an integration contract").

- **Option A — a new app-owned `invite/` package (RECOMMENDED).** A small `app/.../invite/` package: `InviteToken` (the data it carries), `InviteCodec` (`encode(...) → String` / `decode(String) → DecodeResult` with a typed `Rejected`), using stdlib `java.util.zip.Deflater/Inflater` + base64url, off the UI thread for the payload work (`invite_codec_off_ui_dispatcher`). Random-key generation uses `crypto/KeySources.newRandomKey()` (decision 9 `random` source); raw-key export/import uses `ChatKey.export()` / `KeySources.importRawKey(raw)` — both already exist precisely "to feed the future invite feature" (their KDocs say so). The codec depends *downward* on `crypto` for the key bytes only; it owns the JSON/gzip/base64 framing itself.
  - Pros: matches the one-package-per-concern convention; keeps the bearer-token framing out of `crypto/` (the architecture insists the codec is *not* cryptographic — folding it into `crypto/` would muddy the "no hand-rolled crypto" boundary, which explicitly exempts app-owned framing); JVM-unit-testable with no native dependency for the framing (`invite_token_round_trips_owner_to_member`, `invite_decode_rejects_non_owdm_or_malformed_token`); the ZXing round-trip test (`qr_generate_then_decode_recovers_invite`) wraps the same string.
  - Cons: a new package — but that is the established pattern, not a cost.
  - Risks: the token carries the disk **app-password + chat key** in plaintext (bearer). The codec must never log the decoded token and must keep it in memory only (same discipline as `ConnectionConfig.toString()` redaction and `ChatKey.toString()` redaction). The decode path must be strictly reject-don't-guess so a foreign QR (random poster) is a typed `Rejected`, never a partial config (Scenario 4).
- **Option B — fold into `protocol/` or `crypto/`.** Reuse an existing package.
  - Cons: `protocol/` is the *on-disk* WebDAV layout — the invite is explicitly **not** on-disk and **not** an integration contract; putting it there mis-signals it as part of the wire protocol. `crypto/` is audited-primitives-only; the invite is plain encoding, not encryption. Both blur a deliberate boundary. Rejected.

**Recommendation: Option A** — a new app-owned `invite/` package. It pulls the random key from `KeySources.newRandomKey()` and moves raw key bytes via `ChatKey.export()` / `importRawKey()`; the framing (json/gzip/base64url, reject-don't-guess) is its own.

### Choice 3 — Secure at-rest storage of the connection config + app-password

**The problem.** Persist the WebDAV `ConnectionConfig` (URL + username + app-password + chat-root) + which chat is joined (chat-id + community name), so a relaunch re-wires without re-onboarding. The app-password is secret → **Keystore-wrapped** (SC4 forbids `EncryptedSharedPreferences`). The chat key reuses the existing Keystore-wrapped `ChatKeyStore`.

- **Option A — a new `keystore/ConnectionConfigStore` reusing `KeystoreWrapper` (RECOMMENDED).** A new store in the existing `keystore/` package, built exactly like `ChatKeyStore` / `IdentityStore`: serialize the config fields to bytes, wrap via `KeystoreWrapper` under a **new distinct alias** (e.g. `owdm.connconfig.wrap.v1`) and a distinct file (e.g. `connconfig/config.bin`), with a typed load result. Non-secret companions (chat-id, community name, the joined-chat marker) can live alongside in the same wrapped blob (simplest — one atomic blob) or in plain app-private storage; wrapping them too is harmless and keeps one read. The whole blob is device-local, app-private, **never on the WebDAV disk, never logged** (SC4 family; `ConnectionConfig` already redacts its password in `toString()`).
  - Pros: reuses the audited `KeystoreWrapper` mechanism (atomic write, typed unwrap, `AES/GCM-256` Keystore key) — zero new crypto; sits next to its siblings in `keystore/`; the distinct alias keeps it from disturbing chat-key / identity blobs (the established pattern). `config_round_trips_from_secure_store` maps directly.
  - Cons: one more alias/file to manage — but that is the same shape as the two existing stores.
  - Risks: the config holds the app-password; treat it with the same never-log discipline. Because `ConnectionConfig` is `internal`, the store is in-module (fine — `keystore/` is in `app/`).
- **Option B — store the config inside the invite-token blob on disk.** Re-encode and persist the `owdm1:` token.
  - Cons: the token is a *bearer* string designed for out-of-band transit; persisting it as the at-rest format conflates "transport" with "storage" and tempts logging. The Keystore-wrapped binary blob is the right at-rest shape. Rejected.
- **Option C — `EncryptedSharedPreferences`.** Explicitly **forbidden by SC4** (deprecated `androidx.security:security-crypto`). Rejected by constraint.

**Recommendation: Option A** — a new `keystore/ConnectionConfigStore` over the shared `KeystoreWrapper`, distinct alias, app-password never logged; the random chat key continues to use `ChatKeyStore`. This is `owner_create_community_persists_keystore_wrapped_...` and `member_join_from_invite_configures_silently_...` realized.

### Choice 4 — Owner/member role model + Compose state/ViewModel layout

**The problem.** Four screens (owner create-community; invite display string+QR; member join paste+scan; the feed+composer) with off-main-thread KDF/IO and hoisted state per the stack-notes Compose rules. Owner vs member is a **UI role only** — both converge on the same device-local state; the difference is only *how the config arrives*.

- **Option A — one ViewModel per screen, a shared `OnboardingState`, role as data not as a class hierarchy (RECOMMENDED).** ViewModels (`androidx.lifecycle.ViewModel`, state hoisted into the VM per stack-notes) own all network/Room/crypto/codec work on background dispatchers; composables are side-effect-free and render hoisted state + raise events. Suggested split:
  - `ConnectStartViewModel` — the first-launch fork ("create a community" vs "join by invite") — pure navigation.
  - `CreateCommunityViewModel` — validates the form (HTTPS-only refusal before any persist — `owner_connect_cleartext_url_is_refused` / SC13), ensures the on-device identity (`IdentityStore.loadOrCreate()` off-main-thread), mints the random key (`KeySources.newRandomKey()`), persists config + key (Choice 3), auto-creates the community chat (= its community chat, no separate step), calls `EngineWiring.reconfigure(config)` (Choice 1), navigates to the feed.
  - `InviteViewModel` — builds the `owdm1:` string (Choice 2) + the QR `BitMatrix` (Choice 5), shows the bearer-token warning.
  - `JoinViewModel` — accepts a pasted string **or** a scanned string, decodes (reject-don't-guess → the "invalid invite" error state), persists silently, reconfigures, navigates — **never exposes the disk URL/username/password/folder in its UI state** (`member_join_from_invite_configures_silently_without_exposing_credentials`; the VM state must not carry those fields into anything the screen renders).
  - `ChatFeedViewModel` — exposes `MessageStore.observeChat(chatId)` (or `pagedChat`) as UI state; `send(text)` builds a `TextMessage` (sender = the loaded identity, `OrderToken.build(now, self, seq)`), `MessageEnvelope.seal`, `SyncEngine.send(chatId, orderToken, bytes, allMembers=[self], self)`, then `MessageStore.persist` for the immediate echo — all on a background dispatcher.
  - Role = a value (`Role.Owner` / `Role.Member`) carried through onboarding, **not** a class hierarchy — because send/receive/feed are identical for both (key design decision: "owner vs member is a UI role only").
  - Pros: each screen testable via `createComposeRule`; clean state hoisting; no I/O in composables (the off-main-thread discipline is enforced at the VM boundary); the convergence-to-same-state design avoids duplicating the feed/send logic per role.
  - Cons: several small VMs — but that is the idiomatic Compose shape and keeps each within the 300-line/50-line limits.
  - Risks: the join VM must be careful never to surface credentials in observable state (a tempting "show what you joined" screen would leak them) — keep the disk fields out of `JoinViewModel`'s state entirely.
- **Option B — one god ViewModel for all onboarding + chat.** Cons: violates the file/function size limits, mixes the credential-bearing join state with the feed state (raising the credential-exposure risk), harder to test per screen. Rejected.

**Recommendation: Option A** — per-screen ViewModels, role-as-data, all blocking work behind the VM on `Dispatchers.IO`, the feed driven solely by the existing Room `Flow`.

### Choice 5 — QR scanner integration

**The problem.** Generate the QR (owner) and scan it (member), with the **paste path as the mandated fallback** when the camera is denied/absent.

- **Generation:** `com.google.zxing:core` (pure Java, Apache-2.0, no `.so`, no Play Services — stack-notes "QR code generation"). `MultiFormatWriter.encode(token, QR_CODE, w, h, hints) → BitMatrix`, rendered into a Compose `Canvas`/`Image` (one filled rect per black module, or build a `Bitmap`). Modest EC level (`M`), `MARGIN` quiet zone, `CHARACTER_SET=UTF-8`. This is a pure UI/codec consumer of the `invite/` string (Choice 2).
- **Scanning — Option A (RECOMMENDED, per stack-notes): `com.journeyapps:zxing-android-embedded:4.3.0`** wrapped in a Compose `AndroidView` (the library is `View`-based — `DecoratedBarcodeView`/`CompoundBarcodeView`/`BarcodeView`). Wire `resume()`/`pause()` to the composable lifecycle via `DisposableEffect` (`onDispose → pause()`) so the camera does not leak. `decodeSingle`/`decodeContinuous` delivers the decoded string to a callback → hand to `JoinViewModel.decode`. Apache-2.0 (AGPL-compatible), no Play Services (de-Googled-friendly), no native `.so`. minSdk 26 satisfies the 4.x line's minSdk-24 floor with no desugaring.
  - **CAMERA permission flow:** manifest `<uses-permission android:name="android.permission.CAMERA"/>` + `<uses-feature android:name="android.hardware.camera.any" android:required="false"/>` (`required="false"` keeps the app installable on camera-less devices — `manifest_declares_camera_not_required`). Request at runtime via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` + `launch(Manifest.permission.CAMERA)`; check `ContextCompat.checkSelfPermission` first and `packageManager.hasSystemFeature(FEATURE_CAMERA_ANY)`. **Denied / no-camera → degrade to the paste field** (`camera_denied_falls_back_to_paste`). The paste field is always present; the scan affordance is the optional enhancement, never a gate.
  - Pros: drop-in, decode is JVM-testable (`MultiFormatReader` over a `BinaryBitmap`) for `qr_generate_then_decode_recovers_invite`; live camera decode is a documented **manual on-device step** (same class as `connectedAndroidTest` — no CI emulator, decision 8), not a CI gate.
  - Cons: a `View`-in-Compose interop seam (the standard idiom; lifecycle must be wired correctly to avoid a camera leak).
- **Rejected:** ML Kit (proprietary ToS + Play Services — incompatible with the AGPL/de-Googled posture); CameraX+ZXing analyzer (viable later fallback, more wiring than needed for one QR).

**Recommendation: Option A** for both generate (`zxing:core`) and scan (`zxing-android-embedded`), paste fallback mandatory, CAMERA non-required. Add the two ZXing rows + the CAMERA permission to `## Dependencies` / the manifest (plan "Docs to update" → architecture).

### Choice 6 — Reserve (design-only) the community-owner-marker seam

**Decision recorded (DESIGN ONLY — this slice writes nothing):** the community **owner** is identified by an **Ed25519-signed marker** in a future `meta/community.json`, with `owner = <owner signing pubkey>` — **NOT** by "who holds the disk account" (under Topology A all members share one disk credential, so disk-account-holding cannot identify the owner — SC11 / decision 2). The signing key is the owner's existing identity Ed25519 key (decision 10); the marker is signed by it (hard-reject on verify failure, the §10/§11 signed-entry discipline) so it is tamper-evident and cannot be claimed by another member without breaking the signature.

- **Reserved on-disk location/format:** `<community-root>/meta/community.json`, a community-root sibling of the existing `directory/` and `chat-directory/` collections (decision 12 already names "the `meta/community.json` community **owner marker** (community feature) as a community-root sibling of `directory/`" as a deferred-but-clean seam; this records the *field shape*). The byte/path layout, when built, is authored into `docs/protocol/webdav-layout.md` by that future community/owner-migration feature — **not here**.
- **Why design-only now:** reserving the location + the "owner = signed identity-key marker, not account-holder" decision makes the **future owner-migration feature purely additive** (it adds the marker write + a migration flow; it does not have to retrofit a different ownership notion). This slice builds **no** migration flow and writes **no** `meta/community.json`.
- **Downstream dependency noted:** the future **"app self-update via community disk"** backlog feature also builds on this owner marker + **release-signing** (the owner signs the release the community trusts). Recording the owner-marker seam now keeps both future features additive on the same foundation.

This is the realization of the plan's "Owner-migration base = design reserved, not built" decision and the contract's out-of-scope "passing community ownership to someone else."

## Dependency-arrow rule (constrains all six choices)

The existing layering is strictly downward: `ui` (new, top) → `sync` / `invite` / `directory` → `message` / `identity` / `crypto` / `keystore` / `transport` / `protocol`. The load-bearing arrow `identity → directory` (the `RemoteChatProvisioner` seam sits in `directory` precisely because `directory` already depends on `identity`) must **not** be inverted. Concretely for this feature:

- **`ui` composes everything below; nothing below depends on `ui`.** The wiring object (Choice 1), ViewModels (Choice 4), and the QR composables (Choice 5) all live at or above the `ui` layer and reach *down*. **Never** create an `identity → ui` (or any substrate → `ui`) edge — e.g. do not have a substrate call back into a ViewModel; the engine returns typed outcomes (`CycleOutcome` / `SendOutcome` / `ParseResult`) and the UI reads them.
- **`invite/` (new) depends down on `crypto` only** (for `newRandomKey` / `ChatKey.export` / `importRawKey`); it owns its own framing and does not depend on `ui`, `sync`, or `transport`.
- **`keystore/ConnectionConfigStore` (new) depends only on `KeystoreWrapper`** (already in `keystore/`); no upward edge.
- The send path reaches the **same** `SyncEngine` the poll runner uses (Choice 1) — both go *through* `sync`, never around it; the UI does not re-implement `send`/`pollCycle`.

## Plan-revision notes (for the orchestrator — do not edit the plan here)

None blocking. Two small confirmations the coder should carry, already implied by the plan:
- The wiring object should expose a single `reconfigure(config)` the onboarding ViewModels call after first persist, so the `relaunch_with_saved_config_reinstalls_runner` test and the live send path provably share one engine (Choice 1). This is consistent with the plan's contract "App-startup wiring … the single home that composes … for both poll and send" — just naming the entry point.
- `member-index-id` / `senderIdentifier` is a member-identity-derived string; derive it once from the loaded `Identity` and reuse it for both `OrderToken.build` and the engine calls (no plan change — an implementation detail the coder owns).
