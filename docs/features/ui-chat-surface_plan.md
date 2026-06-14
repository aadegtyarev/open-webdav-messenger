# ui-chat-surface — plan

Source: PM feature selection 2026-06-06 ("UI — make the built backend usable"), sliced to a thin working vertical slice and reshaped by two PM scope decisions 2026-06-06 (this conversation): (1) **only the community owner enters disk credentials**; everyone else joins via an **invite (string + QR)** that carries disk access + chat key, hidden from the joining member; (2) the invite carries a **random chat key** so a member types nothing (no passphrase).

> **The first user-facing feature.** Every substrate to date (transport, crypto, identity, message-model, sync, directory, chat-directory) is backend-only and exercised only by tests. Today the app has **no screen, no Compose, and the sync engine is never switched on at runtime** — the `Application` class is an intentionally-empty host and `SyncWorker` runs a no-op `SyncRunner`. This feature turns that headless engine into a usable app along **one thin vertical path** with two roles:
> - **Owner** (holds the disk): connect to the disk → create a chat (app mints a random chat key) → generate an invite (a string + a QR) → read & send in the chat.
> - **Member** (joins): paste the invite string **or** scan the QR → the app silently extracts disk access + chat-id + chat key (the member never sees or types disk credentials) → read & send in the chat.
>
> Rich rendering (Markdown, reactions, replies), discovery (directory/community), public/community chats, and passphrase chats are explicitly deferred to follow-on slices.

## Scenarios

### Owner (the one who holds the disk)
1. **First launch — choose "create a community / I host the disk".** The owner enters the WebDAV details (server URL, username, app-password), the chat folder, and a **community name**. A non-HTTPS URL is refused with a plain-language message (the disk credential must never travel in clear text). The app silently creates the owner's on-device identity keypair on first launch (needed to sign messages and, later, to mark community ownership) — the owner is not asked anything about keys. The app then **automatically creates the community chat** — a mandatory, always-on chat titled by the community name — with a fresh **random chat key** (CSPRNG, Keystore-wrapped), switches the background sync engine on, and drops the owner into that chat's (empty) feed. There is **no separate "create a chat" step**: a community *is* its community chat (one cannot exist without the other — `.ai-pm/backlog.md` "Community = a mandatory all-members community chat").
2. **Generate an invite.** The owner taps "invite". The app produces a single self-contained invite as **both a copyable string and a QR code**, bundling: disk access (URL + username + app-password + chat folder) + the chat-id + the random chat key + the community name. The owner shares it however they like (send the string, or let someone scan the QR). The screen warns plainly: **anyone who gets this invite can read and write this chat and use the disk — share it only with people you trust** (it is a bearer token, not password-protected).

### Member (joins via invite)
3. **First launch — choose "join by invite".** The member either **pastes the invite string** or **scans the QR with the camera**. The camera path requests the camera permission; if it is denied or there is no camera, the paste path is always available as the fallback. The app extracts disk access + chat-id + chat key + community name from the invite, stores them securely (app-password and chat key Keystore-wrapped), switches sync on, and drops the member into the community chat — **the disk URL, username, password and folder are never shown to or typed by the member**.
4. **A broken invite, or a QR that isn't ours.** An incomplete/garbled string, or a scan of some QR that is **not an Open WebDAV Messenger invite at all** (a random QR from a poster/product, or plain noise), is rejected with a clear "this invite isn't valid — check it and try again" message — never a crash. A well-formed invite whose credentials no longer work surfaces a plain connection/read error, not a crash.

### Both roles
5. **Read the chat feed.** The feed shows the chat's messages in conversation order, oldest-to-newest, available instantly and offline (local history, not the network). Message text is shown as **literal plain text** in this slice — Markdown characters show as-is, links are not tappable, nothing is auto-loaded. New messages arriving via the background poll appear in the open feed on their own.
6. **Send a message.** The user types text and sends. Their own message appears immediately (kept locally on send) and is written once to the shared chat log on the disk for other members to pick up on their next poll. A send while the disk is unreachable is not lost — it is kept locally and retried; a retry never produces a duplicate in anyone's feed.
7. **Background catch-up while the app is closed.** With the app backgrounded/closed, the engine keeps polling on its ~quarter-hour schedule; on reopening, everything received meanwhile is already there, in order, no duplicates (existing engine behavior — this feature only switches it on and renders it).

## Existing behaviors this feature touches

(from `docs/user-journeys.md` — what must not break)

- **Journey 1 (send + stay caught up)** — today exercised by the engine + tests only. This feature is the screen that finally renders it; the engine's guarantees must be preserved verbatim (send-once / immediate local copy, background quarter-hour delivery, in-order no-duplicate catch-up, offline-readable history). This feature must **not** change any sync/transport/crypto/message/data behavior — it consumes those seams unchanged.
- **The `random` key source (architecture decision 9)** is wired into production for the **first time** here: the crypto substrate's random-key generation + raw-key import/export (decision 9: "distributed out-of-band via a future invite (string + QR) feature" — that feature is this one) become user-reachable. The substrate is consumed unchanged.
- The **no-op `SyncRunner` default** (benign clean cycle before any config) must keep working: a scheduled poll before connect/join is a benign success, never a crash.
- **Off-main-thread discipline** (architecture constraint): all network, all Room access, and any key work stay off the UI dispatcher.

## Contracts

(the UI plugs into existing engine seams; this slice adds the runtime-config + app-startup-wiring + invite seams the substrates deferred to "the future config/UI feature")

- **An invite token codec (app-owned).** Encode/decode a self-contained invite token, format `owdm1:<base64url(gzip(json))>` (the illustrative format drafted in `.ai-pm/backlog.md`), carrying disk access (URL + username + app-password + chat-root) + chat-id + the raw random chat key + the community name (the community-chat title). **Plain encoding, not encryption** — a bearer token. Decode is reject-don't-guess: an unknown prefix, bad base64url, bad gzip, or a missing/invalid field is a typed rejection, never a partial/guessed config. **Invite generation is deliberately role-agnostic** at the code level (any holder of the config + chat key can mint one) so that "let any member invite newcomers" is later a UI/policy toggle, not a rewrite (PM forethought 2026-06-06).
- **A secure on-device store for the WebDAV connection config** (URL + username + app-password + chat-root) and the random chat key. The app-password and chat key are **Keystore-wrapped at rest** (not `EncryptedSharedPreferences`, which is deprecated — SC4); the chat key reuses the existing Keystore-wrapped `ChatKeyStore`. Never written to the WebDAV disk, never logged.
- **App-startup wiring** (the deferred seam): on launch, when a connection config + a chat exist, build the engine graph and call `SyncRunner.install(...)` + `SyncScheduler.schedule(workManager)`; otherwise leave the no-op runner. This is the single home that composes transport + identity + crypto/message + data into a live `SyncEngine` for both poll (`SyncRunner`) and send.
- **A send entry point** the chat screen calls: build a `TextMessage` → `MessageEnvelope.seal(...)` (sign with the on-device identity, AEAD-seal under the chat key) → mint the §4 order-token + §2 content name → `SyncEngine.send(chatId, orderToken, bytes, allMembers=[self], self)` → persist locally for the immediate echo. (Roster is `[self]` in this slice — no other members are enumerated without the directory, so only the shared-log write happens and peers receive via the full-log poll fallback.)
- **The feed reads** the existing `MessageStore.observeChat(chatId)` / `pagedChat(chatId)` — no new persistence, no Room schema bump.

No new public cross-instance contract: the on-disk protocol (`docs/protocol/webdav-layout.md`) is unchanged. The invite token travels **out-of-band** (shown / copied / scanned), never written to the disk and never to an external system — it is not an integration contract (confirmed by `docs/stack-notes.md` QR sections).

## Key design decisions

- **A community *is* its community chat.** There is no standalone "chat" object to create separately — creating the community auto-creates the one mandatory, always-on community chat (named by the community name, keyed by a fresh random key). One cannot exist without the other (`.ai-pm/backlog.md` "Community = a mandatory all-members community chat + a directory"). Additional chats (DMs, sub-groups) are a later slice.
- **Owner vs member is a UI role only.** Both end up with the same device-local state (config + chat key + identity); the only difference is how that state arrives (owner types it / member receives it in the invite). Sending/receiving is identical for both.
- **Bearer invite, accepted for MVP** (matches the backlog decision). The token is plaintext-secret-bearing; whoever obtains it is in, "forever" (no per-invite revocation under Topology A). Mitigation is procedural (share over a trusted channel) + the on-screen warning. Recorded in the threat model.
- **Owner-migration base = design reserved, not built** (PM decision 2026-06-06). `pm-architect` records the decision that the owner is identified by a **signed identity-key marker in `meta/community.json`** (not by "who holds the account") and reserves that on-disk seam so the future migration feature is purely additive. This slice writes **no** `meta/community.json` and builds no migration flow.
- **ZXing stack** (per `docs/stack-notes.md`, researched 2026-06-06): generate via `com.google.zxing:core`; scan via `com.journeyapps:zxing-android-embedded`; camera permission via the AndroidX `rememberLauncherForActivityResult(RequestPermission())` idiom; paste is the mandatory fallback. ML Kit rejected (proprietary ToS + Play-Services dependency — incompatible with the AGPL-3.0 / de-Googled-privacy posture).

## Stack expectations touched

(from `docs/stack-notes.md`)

- **QR code generation (ZXing core)**: pure-Java `com.google.zxing:core` (Apache-2.0, no native `.so`); render the `BitMatrix` into a Compose `Canvas`/`Image`. Source: ZXing repo/Javadoc (cited in stack-notes).
- **QR scanning (zxing-android-embedded)**: `com.journeyapps:zxing-android-embedded` (Apache-2.0, no Play Services, no native `.so`); wrap its view in a Compose `AndroidView`; confirm `resume()`/`pause()` lifecycle wiring against the sample. Source: journeyapps repo/README (cited in stack-notes).
- **CAMERA runtime permission**: manifest `<uses-permission android:name="android.permission.CAMERA"/>` + `<uses-feature android:name="android.hardware.camera.any" android:required="false"/>`; request via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`; the denied / no-camera path must fall back to string paste. Source: Android developer docs (cited in stack-notes).
- **Jetpack Compose**: composables side-effect-free; recomposition any-order/parallel; move network/DB/crypto off the composable into a ViewModel/background dispatcher; hoist state into the ViewModel; mutate state in callbacks / `LaunchedEffect`. UI logic tested via `createComposeRule`. Source: stack-notes Compose.
- **WorkManager**: the periodic poll interval is clamped to the 15-min floor; schedule via the existing `SyncScheduler` (do not re-implement). Source: stack-notes WorkManager.
- **Room**: no main-thread DB access; the feed consumes `Flow` / `PagingSource` only. Source: stack-notes Room.
- **Android Keystore**: wrap the WebDAV app-password and the random chat key (reuse the existing `ChatKeyStore`); `androidx.security:security-crypto` is deprecated and must not be used (SC4). Source: stack-notes Android Keystore.
- **Kotlin off-main-thread / `java.util.zip` + base64url**: invite encode/decode (gzip via stdlib `java.util.zip`, base64url) runs off the UI thread for large payloads; no `!!` on crypto/IO boundaries. Source: stack-notes Kotlin.

## Interaction scenarios

(the UI shares state with the background `SyncWorker` — both read/write the same Room history and use the same engine seams; not isolated)

- **A background poll lands a new message while the feed is open** → it appears on its own (the feed observes the Room `Flow`); no manual refresh, no duplicate.
- **The user sends while a background poll cycle is running** → both write to the shared `log/` (append-only, content-addressed — no collision) and both persist to Room (idempotent dedup on the §2 message-id). The immediate local echo and the later poll re-fetch of the same message resolve to **one** feed row.
- **A scheduled poll fires before any config exists** (fresh install, before connect/join) → the installed runner is still the no-op default → benign clean cycle, no crash.
- **The app is killed and relaunched with a config already saved** → on launch the wiring re-installs the real runner + re-schedules the poll from the persisted config + stored chat key — no re-onboarding, no re-entry of the invite.
- **The camera permission is denied (or no camera) during join** → the scan path degrades to the paste path; the member can still join.

## Test plan

- Existing tests that must pass: **all existing tests** (additive only; this feature must not change any substrate behavior or touch existing tests).
- New tests:
  - `invite_token_round_trips_owner_to_member`: given an owner config + chat-id + random key + community name, when encoded to `owdm1:` and decoded, then every field (URL, username, app-password, chat-root, chat-id, raw key, community name) is recovered byte-identically.
  - `invite_decode_rejects_non_owdm_or_malformed_token`: given a non-`owdm1:` string (a random QR / noise), a bad base64url / bad gzip, or a missing field, when decoded, then a typed rejection is returned (no partial config, no crash) — covers Scenario 4.
  - `owner_create_community_persists_keystore_wrapped_auto_creates_chat_and_installs_runner`: given a valid HTTPS WebDAV config + a community name, then the community chat is auto-created (no separate step), the config + random chat key are stored Keystore-wrapped, a real `SyncRunner` is installed (replacing the no-op), and the poll is scheduled.
  - `owner_connect_cleartext_url_is_refused`: given an `http://` URL, then connect is refused with a user-facing message and nothing is persisted (SC13).
  - `member_join_from_invite_configures_silently_without_exposing_credentials`: given a valid invite token, when a member joins, then the disk config + chat key are persisted and the member lands in the chat, and the disk URL/username/password are not exposed by the join UI state.
  - `config_round_trips_from_secure_store`: a saved config + key reads back intact (Keystore unwrap).
  - `feed_renders_local_history_in_order`: persisted messages render oldest→newest matching `observeChat`.
  - `feed_shows_message_body_as_literal_plain_text`: a body with Markdown syntax and a URL renders literally — no styling, no tappable link, no auto-load (SC8 deferred-safe default).
  - `send_persists_local_echo_immediately_and_writes_log_once`: sending text persists a local row immediately and issues exactly one shared-`log/` write with `allMembers=[self]` (no change-index notes).
  - `send_then_background_poll_dedups_to_one_row`: send (local echo), then a poll re-lists the same `log/` entry → exactly one feed row (dedup by §2 message-id).
  - `new_message_from_poll_appears_in_open_feed`: with the feed open, a poll that persists a new message surfaces it without manual refresh.
- Interaction scenario tests (one per Interaction scenario above):
  - `poll_during_open_feed_no_duplicate`: covered by `new_message_from_poll_appears_in_open_feed` + `send_then_background_poll_dedups_to_one_row`.
  - `send_concurrent_with_poll_one_feed_row`: set up an in-flight poll listing the just-sent entry; verify one feed row.
  - `poll_before_any_config_is_benign_clean_cycle`: with no config saved, run the installed runner; assert a clean no-op `CycleOutcome`, no throw.
  - `relaunch_with_saved_config_reinstalls_runner`: simulate app start with persisted config + stored key; assert a real runner is installed and the poll scheduled — driving the **same** `SyncRunner.install` path the production `Application` uses (test-wiring-parity: assert `SyncRunner.current()` runs a real cycle, not a hand-rolled engine).
  - `camera_denied_falls_back_to_paste`: when the camera permission is denied / unavailable, the join flow still offers and accepts the pasted string.
- Stack-spec tests (one per stack expectation):
  - `qr_generate_then_decode_recovers_invite`: encode an invite to a ZXing `BitMatrix` and decode it back to the same `owdm1:` string (verifies the ZXing generate/parse contract against the library, cite the ZXing source URL).
  - `poll_scheduled_at_or_above_15min_floor`: the UI schedules through `SyncScheduler` so the enqueued interval is ≥ the WorkManager floor (cite the WorkManager source URL).
  - `feed_consumes_flow_not_blocking_query`: the feed reads the observable `Flow`/`PagingSource` path (no blocking main-thread query; cite stack-notes Room).
  - `invite_codec_off_ui_dispatcher` / `manifest_declares_camera_not_required`: invite gzip/base64 work runs off the UI thread; the manifest declares CAMERA + `camera.any required="false"` (cite the camera-permission source URL).
  - Compose UI tests via `createComposeRule` for: the owner connect+create screen, the invite display (string + QR), the member join (paste + scan-entry) screen, and the feed+composer (cite the Compose testing source URL). The live-camera decode is a **manual on-device step** (same class as `connectedAndroidTest`), noted as such — not a CI gate.

## Docs to update

- `docs/ui-guide.md`: **author the currently-blank template** — interface type (native Android, phone-first, Compose), design system (Material 3, light+dark), layout/interaction/readability conventions, accessibility baseline, and the owner/member onboarding-screen conventions this slice establishes. (Owner: `pm-architect`; authored at plan handoff so the coder has conventions, refreshed post-coding.)
- `docs/user-journeys.md`: update Journey 1's "no visible chat surface yet / engine only" notes; add the **owner-creates-community / member-joins-by-invite** journey (the first onboarding journey), including the bearer-invite caveat. (Owner: `pm-architect`.)
- `docs/architecture.md`: record (a) the **app-startup wiring decision**; (b) the **connection-config + random-key at-rest storage decision** (Keystore-wrapped, EncryptedSharedPreferences rejected per SC4); (c) the **`owdm1:` invite-token format decision** (bearer token, plain encoding not encryption, carries disk access + chat-id + random key, out-of-band only); (d) **reserve the community-owner-marker seam** (`meta/community.json` → signed `owner` identity key) for the future owner-migration feature — design reserved, not built; (e) add the ZXing generate/scan + CAMERA-permission dependency rows to `## Dependencies` and flip the `app/.../ui/` module-map row to Implemented; record that the `random` key source is now wired into production. (Owner: `pm-architect`.)
- `docs/threat-model.md`: add/extend threat rows for (a) **bearer invite token** — a plaintext token carrying the disk app-password + chat key; whoever obtains it (screenshot, shoulder-surf, forwarded message, messenger history) gains full disk + chat access, "forever" under Topology A (mitigation: trusted-channel sharing + on-screen warning; out-of-band only, never on the disk — matches the accepted backlog bearer-invite limit); (b) **device-local storage of the WebDAV app-password + random chat key** (offline extraction on an unlocked/rooted device; mitigated by Keystore-wrapping, same device-local tier as SC4/SC5, same accepted-unlocked-device limit as SC17); (c) **CAMERA permission** (used only for QR scanning; paste fallback means it is optional; no frames stored or sent); (d) **displaying untrusted decrypted text** (literal plain text this slice — no HTML/link/auto-load — so the SC8 markdown surface is not yet opened). Bump `Last reviewed`. (Owner: `pm-architect`; security-bearing project, touches `### Security-relevant surfaces`: secret storage, credential distribution, untrusted-content display, a new permission.)
- `README.md`: refresh the quick-start / "what you can do" front-door beat now that the app has a launchable screen and an owner-connect / member-join-by-invite → chat path (README-currency: quick-start surface). (Owner: `pm-architect`, front-door shape preserved.)

(No new pipeline validator — `docs/stack-notes.md` QR research found none; `CLAUDE.md` Pipeline unchanged.)

## Out of scope

- **Rich message rendering — Markdown subset, the 5 reaction glyphs, reply/quote display.** The next UI slice. Bodies render as literal plain text here. Deferred because it is a self-contained rendering layer with its own untrusted-input attack surface (SC8) and tests.
- **Public / community chats + the public-chat "not protected" warning.** Sibling chat-taxonomy element. Excluded because a public chat is sealed under the **community key**, which this slice does not establish or distribute; it lands with the community-key feature.
- **Passphrase-protected chats (and the wrong-password feedback path).** Sibling chat-taxonomy element. This slice uses a **random** key carried in the invite, so a member types nothing and there is no passphrase to be wrong. Passphrase chats (and their wrong-password UX) are a separate option for a later slice.
- **DH-derived remote private chats.** Sibling element; needs the directory / contact-picker UI. Separate plan.
- **Discovery / directory / chat-directory UI + the local directory cache + Flow.** Later slice; this slice opens the single chat carried in the invite, not by discovery.
- **Owner-migration / community-ownership-transfer flow.** Only the design seam (`meta/community.json` signed owner marker) is **reserved** here by `pm-architect`; the transfer flow itself is a future feature.
- **Controlled / attributable / revocable invites + per-invite or per-member governance.** This slice's invite generation is role-agnostic (any holder can mint one — the inherent Topology-A reality), and "let any member invite" is therefore later just a UI toggle; making invites *controlled* (who may invite, who invited whom, revoke a specific invite) is a future community-governance feature on the identity-signature substrate.
- **QR scanning beyond a single invite, brightness/torch controls, multi-format barcodes.** Single-purpose invite scan only.
- **Multi-chat list / chat switcher; settings (user interval above the floor, foreground-service fast-delivery — open decision 6); account export/restore; local-DB-at-rest encryption (SC17).** All backlog/deferred.
- **Editing existing tests or any substrate behavior.** Additive only.
