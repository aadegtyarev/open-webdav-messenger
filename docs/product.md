# Open WebDAV Messenger — what it is and why

Authored product front door. Owned by `pm-architect`, validated one-pass by the PM. This is **not** the generated map — the contract→features map lives in `docs/product-map.md` (linked from `## Features` below). This file is written **without** any generated-map signature line.

## Why this exists

Open WebDAV Messenger is a native Android text messenger that has **no server of its own**. Instead of a dedicated backend it uses an ordinary cloud disk you already have — Yandex.Disk, Nextcloud, or any WebDAV disk.

Who it is for: privacy-conscious people and small private groups who already have a cloud disk and want to chat **without trusting a messenger operator or running their own server** (there is none).

The problem it solves: it removes the need to run a chat server or trust a third-party messenger. The transport is a file disk you control yourself, and private-chat content is encrypted right on the device, so the disk operator sees only ciphertext.

Source: PM product Q&A at bootstrap (2026-06-03).

## What it does today

The current version is an MVP: text chats with end-to-end encryption over a WebDAV disk. Coverage is derived from the MVP scope and the components in `docs/architecture.md` (contracts in `.ai-pm/contracts/` are not yet established — this is a greenfield start).

Does:

- **Private 1:1 chats** with end-to-end encryption. The key is derived from a per-chat passphrase (Argon2id) shared out-of-band; each message is encrypted separately (XChaCha20-Poly1305).
- **Group chats** — the same shared per-chat passphrase for all members.
- **Public chats** — on a well-known/shared key: readable by anyone with the link and by the disk operator. The app explicitly **warns** that a public chat is not secret and nudges you to create a password-protected private chat for real conversations.
- **5 fixed reactions** on messages.
- **Replies (quoting)** — reply with a quote of the original message.
- **A small Markdown subset** in messages: bold, italic, inline code, code block, blockquote, link. Links are safe — scheme allowlist, visible URL, navigation only on explicit tap.
- **Configurable disk polling interval** — how often the app checks for new messages.
- **Works offline between polls** — messages are cached locally, the feed stays responsive without network.

Does NOT yet (deliberately out of MVP scope):

- **Telegram gateway** — a future feature via an external server.
- **Multiple disks at once** (multi-disk) — one disk for now.
- **Cryptographic contact verification** (X25519 identity keys) — sender authenticity rests only on the shared passphrase; there is no separate identity verification.
- **Separate per-member logins** — members share one WebDAV credential per chat (Topology A); per-member credentials are a future Nextcloud-only enhancement.
- **Protection against message deletion** — there is no protection against message deletion by another member: everyone shares the disk credential; this is a deliberate MVP limitation.
- **iOS** — native Android only.
- **Voice, media, file attachments** — the MVP is text + Markdown only.
- **"Real-time" / faster-than-15-minute delivery** — Android background polling is bounded by a ~15-minute platform floor; faster delivery would need a user-visible foreground mode (a permanent notification) — this decision is still open, see `docs/architecture.md`.

## Documents

Navigation over `docs/` in the PM's language — where to look for what.

- [Architecture](architecture.md) — stack, key decisions, constraints, behavioral contract (chat types, message format, sync rules).
- [User journeys](user-journeys.md) — how the product is used.
- [Threat model](threat-model.md) — what we protect, from whom, and how (untrusted WebDAV transport, E2E encryption).
- [UI guide](ui-guide.md) — conventions of the custom Compose chat interface.

## Features

Contract → features map (what each guarantee includes, which features built it, reviews): [`docs/product-map.md`](product-map.md).
