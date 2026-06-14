# Product brief

> **Last reviewed:** 2026-06-14. The one home for **what this project is and why** — kept current; every feature grounds in it.

## 0. The idea — what is this product?

Open WebDAV Messenger is a native Android text messenger that has **no server of its own** — it uses a user-supplied cloud disk (Yandex.Disk, Nextcloud, any WebDAV share) as its only transport. Messages are end-to-end encrypted on the device so the disk operator sees only ciphertext.

## 1. Customer — who exactly?

**Privacy-conscious people and small private groups** who already have a cloud disk (Yandex.Disk, Nextcloud, etc.) and want to chat without trusting a messenger operator or running their own server. The customer is someone who is willing to configure a WebDAV connection and share a passphrase out-of-band in exchange for a serverless chat with no operator reading their messages.

**Who it is NOT for:** people who need instant (sub-15-minute) message delivery, people who won't configure a WebDAV disk, and people who need iOS (Android only).

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

**Across devices:** a user's chat history lives on their device in a local Room database (unbounded, offline-available). Messages also live on the shared WebDAV disk for a retention window (e.g. 2–4 weeks, configurable), so a second device joining the same chat can catch up within that window.

**Across sessions:** the app polls the disk in the background (WorkManager, ~15-min floor). Between polls, the local history keeps the chat responsive offline. On reconnect, missed messages are caught up automatically within the retention window.

**When a user loses access:**
- **Lost device:** a new device can be set up with the same WebDAV credential + chat passphrase to re-join. Messages within the retention window are catchable from the disk; messages only on the lost device are gone.
- **Lost passphrase:** the passphrase IS the key — there is no recovery. The user must be re-invited or the chat re-keyed (future rotation feature).
- **Lost disk credential:** the host/owner can create a new app-password and distribute it out-of-band. The old credential must be rotated (future feature).

**Others joining:** a new member needs the WebDAV credential + the chat passphrase, delivered out-of-band (a future invite feature will encode this as a QR or string). Under the shared credential model (Topology A), all members share one disk identity.

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
- **Constraints:** native Android only (no iOS); no push notifications; background delivery bounded by Android platform floors (~15 min).

## 7. The case against *(conclude)*

**Strongest reason this will not succeed:** the onboarding friction is too high. A user must (1) install an APK not from a store, (2) create a WebDAV app-password, (3) configure a URL + credential, (4) agree on a passphrase out-of-band with every contact, and (5) accept quarter-hour delivery latency. Each of these steps loses a cohort of potential users; together they filter to near-zero. A messenger lives or dies on network effects — and this one makes joining the network the hardest part.

**Who this is wrong for:** anyone who values convenience over sovereignty; anyone who cannot configure a WebDAV disk; anyone who expects instant delivery; anyone on iOS; anyone who needs a chat they can invite a non-technical friend to in 30 seconds.

**Stop signals:** (a) No real user completes the onboarding flow end-to-end within a month of the first UI release. (b) The Android platform further restricts background execution to the point where 15-minute polling becomes once-per-day, making the app unusable without a foreground service notification that users reject. (c) A cloud-disk provider (Yandex) changes their WebDAV API in a breaking way with no notice and no recourse — the single-provider dependency kills the transport.
