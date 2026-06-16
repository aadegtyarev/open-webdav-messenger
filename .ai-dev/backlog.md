# Backlog

Items noted during planning/review, not yet scheduled. The orchestrator records these.

## Chat model redesign (PM idea, 2026-06-17)

**Problem:** current UX has confusing layers — communities with mandatory group chat + separate group chats + DMs. All are chats at different levels. The join/create flow is inconsistent (DM via QR is "wild").

**Vision:**
- **Single unified chat list** — shows all chats: communities (with mandatory general chat), group chats, DM/private chats
- **Two entry buttons:** "Join" (fully automatic from QR/link) and "Create" (with a checkbox "this is a community" for the community case)
- **Create group chat:** just name + which community it belongs to
- **Create community:** checkbox → community (creates disk + general chat + directory)
- **Create DM:** tap a user in member list → auto-creates DM chat (keys exchanged automatically, both already in same community)
- **No QR for DM** — same-community members auto-exchange keys
- This replaces the current onboarding flow (join/create community screens are separate from chat creation)

**Work needed:** plan this as a feature — redesign onboarding, chat list, member directory integration, DM auto-provisioning.

## In-progress / pending fixes (2026-06-17)

## Audit follow-ups (2026-06-16)

- **(F7) UI automation** — register Compose UI tests (`createComposeRule`) or `connectedAndroidTest` in quality/tools.json. No automated coverage for the chat-surface contract's 8+ behavioural guarantees.

- **Forward secrecy / double ratchet** — per-message key rotation for DM chats (Signal double ratchet). Deferred 2026-06-16 — complex crypto, needs dedicated implementation session.

## UI polish (2026-06-16)

## UI polish (2026-06-16)

- **Font size setting** — user-configurable text scale in settings. Apply to message body, composer, and UI labels. Store in SharedPreferences. Respect system font scale as baseline, let user override.

## Join-flow / community model decisions (PM, 2026-06-03)

These shape the invite / directory / community features below.

- **Single host/owner** — the Yandex-disk owner is the community host: issues invites; only the host can rotate the disk app-password. Accepted single point of trust/control.
- **Bearer invites, accepted for MVP** — whoever scans an onboarding invite is a member forever; no per-invite revocation on Topology A. **BUT** design toward *rotate-with-auto-replace* for individual removal (below) — it makes "kick one person" practical.
- **Rotate-with-auto-replace (individual removal mechanism, enabled by X25519):** to remove one member, the host (1) creates a NEW disk app-password (Yandex allows several concurrently), (2) writes the new password to the disk **encrypted per-remaining-member with each member's X25519 public key** (the removed member is skipped), (3) remaining members sync, decrypt their copy with their private key, auto-update their stored credential, (4) host deletes the OLD app-password → the removed member loses future access, the rest never re-onboard. Caveat: the removed member keeps already-read plaintext and had access until rotation completes; only future access is cut. Requires X25519.
  - **App vs manual split (PM clarification, 2026-06-03):** steps 2-3 (per-member X25519 encryption, disk layout/write, remaining members auto-updating their stored credential) are **fully automated by the app** from the host's account — no hand-editing files, no hand-encrypting. Steps 1 and 4 (create / delete the actual Yandex app-password) are **manual in Yandex ID settings** — Yandex exposes no API for a WebDAV client to manage account credentials, so the host creates the new app-password in Yandex and pastes it into the app once, and later deletes the old one in Yandex. On **Nextcloud (Topology B)** app-password creation has an API → could be fully app-driven. Yandex.Disk OAuth tokens are not a fit (bound to the host's personal account, not shareable as the community credential).
- **X25519 pulled INTO near-term scope** (was deferred): per-user identity keypair, public key published + signed entries in the directory, safety-number verification via QR. Unlocks: anti-impersonation in the directory, remote private chats between members already sharing a disk, and the rotate-with-auto-replace mechanism above.
- **Community = a mandatory all-members community chat** + a directory. Onboarding a member = joining the community = landing in the community chat (the always-on group where everyone is). Additional chats (DMs, sub-groups) layer on top.

## Upcoming features (revised order)

- **X25519 identity (NEAR-TERM — pulled forward)** — per-user identity keypair on top of the crypto substrate (done). Publish the public key in the directory; sign directory entries; verify peers via QR safety-numbers. A fourth key source: DH between two members' keys → a symmetric key fed to the existing XChaCha20-Poly1305 AEAD, enabling remote private chats with no secret exchanged over a channel. Also the prerequisite for the rotate-with-auto-replace removal mechanism. Builds on `crypto/`.
- **User/chat directory on the disk** — discovery of users and chats within a community that shares a disk. **Decision (PM, 2026-06-03): the directory is ENCRYPTED with a community key distributed at onboarding (default = encrypted, NOT plaintext).** The community key rides in the onboarding bundle (alongside disk access); it is NEVER the disk app-password (the operator holds that). Caveat to state in the feature: file/folder structure, sizes, timestamps and write activity still leak some metadata to the disk operator even with an encrypted directory; full traffic-metadata hiding (padding/cover-traffic/name randomization) is a separate, larger concern, out of MVP.
- **Invite / onboarding** — a self-contained **string + QR** (no server, no URL — there is no server) carrying disk access (URL + app-password) + chat-id + chat config + (for random-key private chats) the content key + (later) the community directory key. The crypto substrate already exposes random-key generation and raw-key import/export for this. Illustrative wire format drafted (`owdm1:<base64url(gzip(json))>`, ~264 chars, scannable QR).
  - **Two-layer model (PM clarification, 2026-06-03) — design invites against this:** *community membership* (= holding the disk app-password; one disk per community) and *chat membership* (= holding a chat's `chat-id` + key; many chats per disk) are **separate layers**. So there are two invite kinds: a **community-onboarding** invite (disk access only, no chat-id → chats then discovered via the directory) and a **chat-join** invite (chat-id + key, + disk access if not yet a member). Bundling both in one string is a convenience, not a requirement.
  - **Removal semantics:** removing someone from a *chat* = **re-key that chat** (they lose new messages but keep disk access). Removing from the *community* = **rotate the disk app-password** (everyone re-onboards — heavy on Topology A; single-person revocation needs Topology B / Nextcloud, deferred). Under Topology A a chat-removed member still has disk access → can see ciphertext/metadata and can still delete files (the flat-trust limit). True membership/removal hardening (signatures, Topology B) is future.
- **Message-model** — the plaintext envelope field structure (message-id echo, chat-id, reply-to, reaction, body serialization) that lives inside the AEAD ciphertext. The crypto substrate seals/opens opaque bytes; this feature defines the bytes.
- **Compression** — wire DEFLATE into the envelope `codec-id` (currently always 0x00). Compress-then-encrypt, per-message, bounded inflate (zip-bomb guard) — see architecture decision and stack-notes.
- **UI** — Compose chat surface, passphrase entry, the public-chat "not protected" warning, wrong-password feedback.
- **Forward secrecy / double ratchet** — the genuinely-stronger private-chat path (per-message keys); deferred.
- **Local message history encryption at rest** (PM decision, 2026-06-04, from the `sync` feature). The local Room DB holds decrypted, signature-verified message history; today it is app-private + device-lock/OS-storage-encryption protected but NOT additionally app-encrypted (recorded as **SC17 / T16** in `docs/threat-model.md`; surfaced by the `sync` feature review (transient stamp, deleted after ship)). On an unlocked, in-hand device the history is readable. Backlogged feature: encrypt the local DB at rest with a Keystore-wrapped key (e.g. SQLCipher or a Keystore-AES-wrapped store), so it matches the device-local secret tier the chat/identity keys already use. PM chose backlog (not MVP-blocking) — full at-rest DB encryption is a planned hardening, not a current guarantee.

## Architectural forethought (PM, 2026-06-03 — not yet scheduled)

- **Community ownership transfer / migration.** The host should be identified by a **signed identity-key marker in community metadata** (e.g. `meta/community.json` → `owner = <host Ed25519 public key>`), NOT implicitly by "whoever holds the Yandex account". This decouples *who is host* from *who holds the account* and makes transfer verifiable using the identity substrate's Ed25519 signatures:
  - **Same-disk handover:** the outgoing host (i) hands Yandex account access out-of-band (so the new host can create/delete app-passwords), and (ii) writes a **transfer record signed by the old host's key** naming the new host's identity key; members verify the signature and update their notion of host. Reuses `identity` signing.
  - **Disk migration (new account/disk):** a **signed "community moved" pointer** on the old disk → the new disk's coordinates, delivered to remaining members **sealed per-member** (same machinery as rotate-with-auto-replace). Reuses `identity` sealed-box + signing. Essentially re-onboarding driven by a signed pointer rather than manual.
  - The `identity` feature (in progress) is the enabler; the ownership-transfer flow itself is a later feature.
- **Disk space / retention — disk = bounded retention WINDOW (CORRECTED 2026-06-03, supersedes the earlier "pure transient buffer" note).** PM pushed back: a pure transient buffer (delete-after-read + short TTL) breaks multi-user/offline — an offline member beyond the TTL loses messages, a new member gets no history, and in a group "delete after the FIRST reader reads" loses it for everyone else. Corrected three-level model:
  1. **Shared encrypted per-chat log (not per-recipient delete-after-read).** The disk holds a **retention window** of each chat's recent (encrypted) messages — by time (e.g. 2–4 weeks, configurable) and/or count. Pruning is **window-based only (TTL/size), never read-based**. This serves: offline members (catch up on everything within the window on return), new members (get the window as starter history, or "from join point only" per chat policy), and bounds disk to the window.
  2. **Full history lives locally on each device (Room)** — unbounded locally; the disk copy is only the shared catch-up window.
  3. **Per-user change-index for cheap polling:** one poll returns "which chats changed for you and from which cursor" (the aggregated changeset the PM asked for at bootstrap), then the client fetches new messages from those chats' logs. Disk holds two structures: shared per-chat logs (messages, windowed) + per-user change index (what's new for me).
  - **Honest limit:** offline longer than the window still loses the oldest (unavoidable without a server and with finite disk; make the window generous + configurable). Media/attachments (future) need stricter caps.
  - **This REVISES architecture decision #2 (aggregated sync) and the `webdav-layout` per-recipient-inbox model** ("inbox fan-out + delete-after-read" → "shared chat log + per-user change index + retention window"). Low-level transport verbs are unchanged; the on-disk LAYOUT changes. To be reconciled by pm-architect when the **sync / message-model** features are planned — they own the webdav-layout rework and the decision #2 update.

## Host-governed polling floor (PM idea, 2026-06-04 — refines open decision #6)

The community **host sets a community-wide minimum polling interval** (they own the shared disk, see the load, and are responsible for all members + the Yandex 429 rate-limit budget). Shape:
- The minimum lives in **signed community metadata** (`meta/community.json`, Ed25519-signed by the host — uses the `identity` substrate). Non-secret.
- **Each member can only configure their interval UPWARD from that floor.** Effective interval = `max(member's choice, community-minimum, platform-floor)` — where the platform-floor is the WorkManager ~15-min periodic floor (or lower in the foreground-service mode of decision #6).
- **Enforcement is cooperative / honest-client** (a member could poll faster, hurting the shared disk; acceptable in a trusted community — record in the threat model as a cooperative assumption, not an enforced guarantee). Over-polling is hard to attribute under the one-shared-credential model (reads are mostly invisible per-member).
- **Future:** auto-tune the community minimum from member count / observed 429 pressure (host-driven, validated on real tests).
- **Partially resolves open architecture decision #6** (polling cadence): the *minimum* is host-governed community policy; the WorkManager-floor-vs-foreground-service mechanism is still the separate open question. Belongs to the **community** feature (needs `meta/community.json` + host signing) layered over the `sync` poll loop.

## Downstream expectations recorded from review

- **Chat-model must cache the derived chat key in memory** (plan-checker note, crypto review 2026-06-03). The crypto substrate's "no re-derivation per message" relies on the caller holding the `ChatKey` / using `ChatKeyStore.load()` rather than re-running Argon2id (INTERACTIVE = slow) on every send/receive. The chat-model/sync feature owns this caching; do not re-derive per message.

## Feature blockers recorded from quality sweep (audit-2026-06-06)

- **✅ RESOLVED 2026-06-06 by `x25519-identity`** — fixed additively: the new `IdentityCrypto.deriveRemoteChatKey(...)` binds the chat-id into a v2 KDF context (`"owdm/x25519-chatkey/v2" ‖ 0x1F ‖ chatId`), so production DH-derived chat keys are now distinct per chat-id; `agreeChatKey` left signature-stable as the bare pairwise primitive (KDoc-marked superseded-for-chat-keys). `openSealed` box-secret wipe added; `importRawKey` wipe deferred (an existing test reads the input array → would change observable behaviour). Original blocker text kept below for history.
- **BLOCKER — `IdentityCrypto.agreeChatKey` must bind the chat-id into the KDF before any X25519-derived-chat-key feature ships** (quality-sweep finding D10, PM decision 2026-06-06: backlog as a feature blocker). Today the per-chat key is `keyedHash(CHATKEY_KDF_CONTEXT, shared)` with a **fixed** context constant; the only varying input is the X25519 DH `shared`, so **two distinct chats between the same identity pair derive the identical `ChatKey`**, breaking per-chat key isolation. `agreeChatKey` has **zero production callers today** (deferred-feature scaffolding) so there is no current risk — but the **remote private-chat, directory private-chat, and rotate-with-auto-replace** features (all in the X25519 cluster above) consume it. The feature that first wires `agreeChatKey` into production **must** first change the derivation to mix the chat-id (and ideally a per-chat salt) into the KDF input so each chat gets a distinct key. CONFIRMED by sweep verification. Owner: whichever of the private-chat / directory / rotation features lands first. The accompanying lower-severity deferred-crypto consistency items (`importRawKey` / `openSealed` raw-overload don't wipe caller secrets) should be settled in the same feature.

## Upcoming features (added 2026-06-06)

- **Client-side account export / restore (device-loss recovery)** (PM idea, 2026-06-06). Let a user **back up their access on one device and restore it on another** after losing/replacing a phone — so they don't lose their community membership and chats. **What is exported:** the device-local secret bundle needed to reconnect — disk access (WebDAV URL + app-password), the community key, the per-chat keys (`ChatKeyStore`), and the user's identity keypair (Ed25519 + X25519 secret keys). **Mechanism (PM):** produce an **encrypted text blob** and hand it to **Android's standard Share sheet** (`ACTION_SEND`) so the user routes it wherever they want (their own cloud, a note, etc.); restore reads the blob back. Password-protected (Argon2id → XChaCha20-Poly1305 AEAD — reuse the existing crypto substrate's passphrase key source).
  - **Security design tension to resolve at planning (security-bearing):** this **deliberately moves device-local secret material OFF the device** — a conscious exception to SC4/SC5 (keys are Keystore-wrapped, device-local, never leave) and adjacent to SC21 (never in source — here it is never in *plaintext* anywhere). Therefore the **password must be effectively mandatory** for the secret-bearing export: an "encrypted but no user password" option can only mean a *device-bound* Keystore key, which by definition **cannot be restored on a new device** — defeating the whole recovery purpose. So the realistic design is **password-required** (the password is the only thing that travels in the user's head, not on the lost device). Frame the PM's "optional password" accordingly when planned: optional is not viable for cross-device secret restore; surface this trade-off to the PM.
  - **Threat-model additions when planned:** a new asset/threat for the exported blob (offline brute-force of a weak export password → full account takeover incl. impersonation via the identity secret keys; mitigate with a strong memory-hard KDF + a password-strength nudge), and the restore-path trust (a tampered/forged blob). Record whether identity secret-key export is in or out (exporting the identity `sk` enables seamless restore but widens the blast radius of a cracked blob to impersonation; a lighter export of disk-access + community/chat keys only, regenerating identity on restore, is the smaller-blast-radius alternative — a PM scope choice).
  - Pairs naturally with the **UI feature** (the export/restore screens + the Share intent are user-facing). Depends on nothing in the X25519 slice; can be planned independently once there is a UI surface.

## Audit 2026-06-17 findings (dispatched)

### HIGH (fixed — PR #75)
- ~~Stale test PollIntervalClampingTest (60→15)~~ ✅
- ~~Stale KDoc FastPollManager.kt:26~~ ✅

### MEDIUM

- **ExportRestoreActivity exported without guard** — `AndroidManifest.xml:37`: any app can start the export/restore UI. Add intent-filter or custom permission so only Share sheet / internal nav can launch it. **Next fix.**
- **connectedAndroidTest not in quality registry** — instrumented tests (Keystore, Room migrations, native .so) exist in `androidTest/` but aren't gated in `tools.json`. Add `./gradlew connectedAndroidTest` row (requires emulator/device).
- **Debug logging of full member name map** — `AppContainer.kt:325` logs all name mappings at DEBUG level. Wrap in `BuildConfig.DEBUG` check or remove before release.

### LOW

- **`bound [?]` in architecture.md:47** — bound IS measured (1 MiB, readCapped). Replace `[?]` with actual value.
- **markdown module "Planned" status** — add decision reference or "DESCOPED for MVP (plain-text only per contracts)".
- **stack-notes.md last-reviewed date** — 2026-06-03, pre-dates recent features. Refresh.
- **No SAST/dependency-CVE scanner** — add OWASP dependency-check or similar to `tools.json` review beat.
- **No coverage threshold enforced** — architecture.md:140 says ≥80% but no JaCoCo/Kover row in tools.json.
- **JNA 5.13.0 age** — conscious pin (→ `docs/decisions/jna-pin.md`). Periodic re-evaluation noted.

## Minor / cosmetic (noted during review)

- **`crypto/KeySources.kt` is classified `data` (not text) by `file(1)`** — a pre-existing encoding quirk (present in `main`, unrelated to the feature that surfaced it during the x25519-identity Pass-2 review). It compiles, ktlint/lint/tests are all green, so it is cosmetic, but a stray non-UTF-8 byte in a source file is worth a one-line cleanup (re-save as clean UTF-8) so `git diff` stops rendering it as `Bin` and tooling treats it as text. Trivial; fold into any future touch of that file.
