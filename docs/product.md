# Product brief

> **Last reviewed:** 2026-06-14. The one home for **what this project is and why** — kept current; every feature grounds in it.

## 0. The idea — what is this product?

Open WebDAV Messenger is a native Android text messenger that has **no server of its own** — it uses a user-supplied cloud disk (Yandex.Disk, Nextcloud, any WebDAV share) as its only transport. Messages are end-to-end encrypted on the device so the disk operator sees only ciphertext.

## 1. Customer — who exactly?

**Privacy-conscious people and small private groups** who already have a cloud disk (Yandex.Disk, Nextcloud, etc.) and want to chat without trusting a messenger operator or running their own server. The customer is someone who is willing to configure a WebDAV connection and share a passphrase out-of-band in exchange for a serverless chat with no operator reading their messages.

**Who it is NOT for:** people who won't configure a WebDAV disk, and people who need iOS (Android only). The current 60-second personal polling default selects a foreground service when below WorkManager's 15-minute floor; Android requires a persistent notification. Users can choose a slower interval to stop fast mode. This behavior is currently automatic, not opt-in.

## 2. Problem — from their point of view

"I want to chat with a few people privately, but I don't want to run a server, and I don't trust any messenger company with my messages. I already have a cloud disk — can't that just be the server?"

The customer removes the need for a dedicated chat server or trusting a third-party messenger. The transport is a file disk they already control, and private-chat content is encrypted right on their device, so the disk operator sees only ciphertext.

## 3. Discovery & onboarding — zero to working

**Discovery:** the project is an open-source (AGPL-3.0) Android app on GitHub. Users find it through the repository, word of mouth in privacy-focused communities, or direct recommendation.

**First steps from nothing to working:**

1. Install the APK (from GitHub Releases or build from source).
2. Create a WebDAV app-password on your cloud disk (Yandex.Disk, Nextcloud, etc.).
3. In the app, configure the WebDAV URL + app-password (the "connection config").
4. Agree on a chat passphrase with your contact(s) out-of-band (Signal, in person, etc.).
5. Create a chat — the app derives an encryption key from the passphrase and creates the on-disk folder structure.
6. Send a message — the app encrypts, signs, and writes it to the shared disk; the other person's app polls and surfaces it.

**Prerequisites:** an Android device, a WebDAV-capable cloud disk account, and an out-of-band channel to share the chat passphrase and disk credential. Getting started requires the user alone plus one coordination step with their contact(s).

## 4. Continuity & recovery

**Across devices:** a user's chat history lives on their device in a local Room database (unbounded, offline-available). Messages also live on the shared WebDAV disk (a time-based retention window — 14 days by default, pruned automatically after each poll cycle; see `docs/protocol/webdav-layout.md` §1.4), so a second device joining the same chat can catch up recent messages. A restored backup reconstructs registered community connections, chat registries, keys, identity, and active-community selection; Room history remains device-local and is not transferred.

**Device-loss recovery:** the app provides a versioned **export/restore** mechanism: the user can export all registered community connection settings and chat registries, community/chat keys, identity keypair, and active-community selection as a password-encrypted blob via the Android Share sheet, and restore it on a new device with the same password. Restore is available from the first-launch Start screen or Settings. Payload validation completes before stores are changed; store-write failure attempts snapshot rollback, but crash-atomicity across independent Keystore/filesystem stores is not guaranteed. Legacy v1 exports remain readable, but they lack membership registries and may not rebuild a usable joined runtime. The export password is mandatory — a device-bound key cannot be transferred across devices. Identity secret keys are included; a cracked export password permits impersonation.

**Across sessions:** the app polls the disk in the background through WorkManager and, when the effective interval is below the WorkManager floor, uses `FastPollService` with a persistent notification. Android 13+ notification permission is offered contextually in Settings; a denied request is not repeated automatically and a system-settings recovery link is provided. Background execution and exact delivery time remain subject to Android scheduling, battery controls, network availability, and WebDAV availability. The current personal interval default is 60 seconds, so the foreground service is automatically selected until the member chooses an interval at or above the WorkManager floor; it is not currently an opt-in-only setting. Between polls, local history keeps the chat responsive offline.

**When a user loses access:**
- **Lost device:** use the export blob + password to restore on a new device. Recent messages on the shared disk are catchable; messages only on the lost device are gone.
- **Lost export password:** the export IS the recovery path — without it, a lost device means lost account secrets. A new identity + re-join is required.
- **Legacy export without community registry:** some old backups can restore secrets/configuration but cannot reconstruct all joined community/chat coordinates; restore reports when no usable runtime can be activated.
- **Lost passphrase:** the passphrase IS the key — there is no recovery. The user must be re-invited or the chat re-keyed (future rotation feature).
- **Lost disk credential:** the host/owner can create a new app-password and distribute it out-of-band. The old credential must be rotated (future feature).

**Others joining:** a new member receives the current bearer invite by QR or string through a trusted out-of-band channel. The token contains disk access and chat-key material; anyone holding it can join and use the shared disk credential. Under the shared credential model (Topology A), all members share one disk identity.

## 5. Competition / the incumbent

**What the customer uses today:**
- **Signal / WhatsApp / Telegram:** trusted-messenger model — the operator sees metadata and (in non-E2E modes) content. The customer who wants serverless chat rejects this.
- **Briar:** peer-to-peer over Bluetooth/Tor, no server — but Android-only, no cloud-disk transport, and different connectivity model.
- **Delta Chat:** email as transport — closest analogue, but uses email servers (IMAP/SMTP), not a file disk. The customer with a cloud disk but no desire to run email sees WebDAV as simpler.
- **Manual file exchange:** sharing encrypted text files via a shared folder — works, but has no chat UX (no threading, no reactions, no polling).
- **Doing nothing:** using a trusted messenger and accepting the operator risk.

**Why this is meaningfully different:** it is the only chat that uses a **file disk you already have** as the entire server substrate. No operator, no federation, no P2P overlay — just files on a disk. The transport primitive is `PUT`/`GET`/`PROPFIND`, not a message queue or relay.

## 6. Viability — who runs and funds it

- **Who operates it:** the user operates it — there is no service to run. The cloud disk is the user's own account. The app is a standalone APK.
- **Who funds it:** solo hobby project; no funding, no monetization. No server costs — the user pays their own cloud disk (free tier sufficient for text).
- **Licensing:** AGPL-3.0 — copyleft, source stays open.
- **Compliance:** GDPR/privacy responsibility rests with the user (they control the disk and the keys). The app processes no data on any server.
- **Constraints:** native Android only (no iOS); no push notifications; WorkManager periodic work has a 15-minute floor and may be deferred; the current 60-second personal default automatically enables foreground fast polling below that floor, requiring a persistent notification. Android scheduling and battery restrictions still prevent exact delivery guarantees.

## 7. The case against *(conclude)*

**Strongest reason this will not succeed:** the onboarding friction is too high. A user must install an APK, configure or receive WebDAV access, and coordinate trusted invite sharing out-of-band. Background delivery is best-effort: WorkManager has a 15-minute floor and the current 60-second default selects fast polling with a persistent notification, which has a battery and notification-permission cost. Each step can lose potential users; a messenger still depends on network effects and this one makes joining the network unusually involved.

**Who this is wrong for:** anyone who values convenience over sovereignty; anyone who cannot configure a WebDAV disk; anyone who expects instant delivery without a persistent notification; anyone on iOS; anyone who needs a chat they can invite a non-technical friend to in 30 seconds.

**Stop signals:** (a) No real user completes the onboarding flow end-to-end within a month of the first UI release. (b) The Android platform further restricts background execution to the point where 15-minute polling becomes once-per-day, making the app unusable without a foreground service notification — the foreground service addresses this, but user rejection of the persistent notification is the compensating risk. (c) A cloud-disk provider (Yandex) changes their WebDAV API in a breaking way with no notice and no recourse — the single-provider dependency kills the transport.
