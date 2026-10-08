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

**Onboarding and recovery entry:** The app starts at `StartScreen`, offering
Create, Join, and Restore account. Create/join remains linear; no chat feed is
reachable until a community runtime is configured. Restore is a separate path
and is not blocked by create/join. After create, join, or a successful v2
restore, the home screen is `UnifiedChatListScreen` — a flat list of chats across
joined communities. Cold start restores the persisted active community without
forcing a feed open. Legacy backup content that lacks registry data may not
reconstruct a usable runtime.

**Destination restoration and Back:** The current destination is saveable across
activity recreation. System Back returns from the feed to Chats and from Invite
to the feed; first-launch onboarding returns to Start. Toolbar Back from a feed
also returns to Chats. A delayed open may install a chat only if its community
and runtime selection are still current. Destination state does not substitute
for persistence of community/chat data.

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

**Ordering (3-order):** Messages appear oldest→newest by the sortable message
order token. The list follows appended messages only when the user was already
at or near the bottom; when reading history, new messages do not steal focus.
Read marking uses the latest visible message IDs/state for the active chat.

**Plain text only (3-text):** Message bodies are displayed as literal plain
text. No markup rendering, no linkification, no auto-loading of remote content.
This is a conscious security decision — the renderer never interprets any markup
language.

**Offline readiness (3-offline):** The feed shows whatever messages are in the
local Room database. No network indicator, no "pull to refresh" — the underlying
sync cycle handles freshness transparently. One send is reserved per draft
revision before asynchronous work starts, preventing a double tap from producing
two messages. A send creates a local echo only after its retryable envelope is
durably persisted; the persistence callback and draft compare-and-clear run on
the UI dispatcher with draft edits. The draft clears after persistence succeeds
and is not erased by a stale completion.

**Send failure and retry (3-send-fail):** A failed or uncertain send remains in
the feed with truthful local status. A transient error below the draft clears
when the draft changes or a send succeeds. Automatic sync retry and explicit
retry reuse the original persisted envelope and message ID; they do not create
a replacement row or payload. Retry work is restricted to the message's local
community owner and only one delivery attempt may claim a message ID at a time.
A recoverable draft is retained if local persistence fails.

**Runtime scoping (3-scope):** Feed and invite state is scoped to the active
community and chat. Room history, unread/read state, and sync cursors are
partitioned by local community plus chat ID, so identical protocol DM IDs in
different WebDAV roots cannot merge feed state or advance one another's cursor.
Wire chat IDs and crypto are unchanged. History whose prior community owner was
not persisted is retained under an unscoped legacy namespace and is not exposed
in a joined feed rather than guessed into a community. Changing scope must not
display or send with stale state from the previous runtime. Credential-only
runtime rotation preserves the active graph's roster and member names so peer
recipients and change-index notifications remain intact. Group creation uses
the selected community's stored connection and runtime graph and opens the
created group in that community. A delayed open atomically installs only against
the graph and selection revision it captured. Credential rotation changes the
stored connection credentials while retaining the persisted community anchor
chat ID and name, even while a group or DM is open.

**Accessible retry control:** Failed-message retry is a labelled button with a
minimum 48×48dp target. It invokes the existing retry operation for the original
message; it does not change envelope or message-ID semantics.

**Related surfaces:** [Account recovery](account-recovery.md),
[Community settings](community-settings.md), and
[Background delivery](background-delivery.md).
