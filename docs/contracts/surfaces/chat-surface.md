# Chat surface contracts

> Status: **live** — v0.14.0 (2026-06-15). These behavioural guarantees persist
> across future releases unless explicitly deprecated with a migration path.

## Onboarding flow

**Create community (1-create):** The user enters a community name, the system
generates a fresh identity and an `owdm1:` invite token. The resulting
configuration is persisted to `ConnectionConfigStore` before the flow completes.
If persistence fails, the user sees an error and can retry — no partial state is
left behind.

**Join community (1-join):** The user scans or pastes an `owdm1:` invite token.
The system decodes it (see Invite format), imports the community identity, and
persists the configuration. An invalid, expired, or tampered token is rejected
with a user-visible error — never silently ignored, never a crash.

**Onboarding is linear, not skippable:** The app starts at `StartScreen`. No
chat feed is reachable without a configured community. The flow cannot be
back-navigated past `StartScreen` (the back stack is cleared on completion).
After onboarding completes, the home screen is `UnifiedChatListScreen` — a flat
list of all chats across all joined communities.

## Invite format

**Token scheme (2-format):** Invite tokens use the `owdm1:` URI scheme with a
`base64url(gzip(json($fields)))` payload. The JSON fields are: community name,
community ID, WebDAV root URL, chat ID, raw chat key bytes (32 bytes), and a
protocol version marker.

**Reject-don't-guess (2-reject):** A wrong prefix (`http://`, a random QR code,
noise), bad base64url, corrupt gzip, or any missing/invalid field produces a
typed `Result.Rejected` — never a partial config, never a crash, never an
exception propagated to the UI layer.

**Bearer token, not encrypted:** The token carries plain (not encrypted) fields.
Whoever holds it can join — this is by design. The on-screen warning at token
display and the trusted-channel sharing instruction are the only mitigations.

**QR code (2-qr):** The token is encodable as a QR code (`QrEncoder`) for
camera-based sharing. The generated QR uses error-correction level M (~15%).

## Chat feed

**Single source of truth (3-source):** The feed observes messages solely from
the Room database (`MessageStore.observeChat`). There is no second message list,
no second persistence path. A background poll that lands a new message surfaces
it automatically; a send echo + later poll re-fetch deduplicate to one row by
message ID.

**Ordering (3-order):** Messages appear oldest→newest (ascending server
timestamp). The list auto-scrolls to the bottom on new messages only if the user
is already at the bottom; if they have scrolled up to read history, new messages
do not steal focus.

**Plain text only (3-text):** Message bodies are displayed as literal plain
text. No markup rendering, no linkification, no auto-loading of remote content.
This is a conscious security decision — the renderer never interprets any markup
language.

**Offline readiness (3-offline):** The feed shows whatever messages are in the
local Room database. No network indicator, no "pull to refresh" — the underlying
sync cycle handles freshness transparently. The send button is always enabled;
messages queued for send appear locally immediately (optimistic echo).

**Send failure (3-send-fail):** If a send fails after the local echo, an error
message appears transiently below the draft field. It clears when the draft
changes or a subsequent send succeeds. Failed messages are not retried
automatically — the system's sync cycle will pick them up on the next poll.
