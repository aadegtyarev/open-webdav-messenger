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
forcing a feed open. A successful restore launched from Settings returns to Chats
and invalidates remembered account-scoped role/policy values, including when the
active community ID is unchanged. Legacy v1 restore is restricted to the empty-
target single-chat mapping defined by the account-recovery surface.

**Destination restoration and Back:** The current destination is saveable across
activity recreation. System Back returns from the feed to Chats and from Invite
to the feed; first-launch onboarding returns to Start. Toolbar Back from a feed
also returns to Chats. Group creation for a non-active community switches and creates under one account-mutation barrier; its activation path uses the already-held exclusive operation. A delayed open may install a chat only if its community
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
exception propagated to the UI layer. New tokens carry signed strict
`access=public|private`; missing, invalid, or tampered access (and legacy v1)
is rejected before account mutation with localized guidance to request a fresh
invite. Access selects roster behavior only and grants no community authority.

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

**Local feed-open latency (3-open):** Every chat type—General, group, and DM—
installs its local runtime and displays the Room-backed Feed without waiting for
WebDAV roster, directory, or metadata reads. A slow or unavailable network does
not delay the local Feed. Opening uses account stability only; it does not wait
on the network-poll serialization barrier.

**Offline readiness (3-offline):** The feed shows whatever messages are in the
local Room database. There is no global network indicator or "pull to refresh" —
the underlying sync cycle handles freshness transparently. Group and DM feeds
open before remote directory reads; while verified recipients load, the top bar
exposes accessible indeterminate progress and Send is disabled. An unavailable
roster keeps the feed/history/navigation usable, disables Send, and shows a
compact status with a retry route. General may be Ready immediately when its
existing verified recipient/key state is sufficient. A new send is accepted
only from a Ready verified-recipient snapshot; it atomically uses that snapshot
for both the durable retryable envelope recipients and immediate fan-out. One
send is reserved per draft revision before asynchronous work starts, preventing
a double tap from producing two messages. A send creates a local echo only after
its retryable envelope is durably persisted; the persistence callback and draft
compare-and-clear run on the UI dispatcher with draft edits. The draft clears
after persistence succeeds and is not erased by a stale completion.

**Verified roster cache (3-roster-cache):** A bounded device-local cache stores only
verified public participant entries, encrypted with the Android Keystore. Cache
identity is exact community/chat scope and is bound to the community key, chat key,
and account public identity. A valid hit installs Ready before the asynchronous
WebDAV verification refresh only if the lookup generation is still current at the
exact-context apply; invalidation racing a cache hit rejects that apply. A General
continuation retains its originating request token and exact installed runtime, so
a newer open cannot redirect it to another chat. This cached Ready state is
visually normal and keeps Send enabled. Cache application and cache-writing
commits take locks in cache → shared commit coordinator → request/selection/runtime
order. Invalidation advances the generation under cache state before removing data,
fencing prior lookups. A current verified refresh replaces the runtime roster and
durable cache under the shared commit coordinator; strict atomic replacement keeps
the old encrypted cache if replacement fails, while the verified roster may still
update the active runtime. A refresh failure preserves Ready only after cached
application succeeded, without a foreground error.
Without a valid hit, Loading keeps Send disabled, shows localized input guidance
and an accessible progress action, and transitions to Ready or Unavailable after
remote verification. Cache data is strictly bounded and malformed data fails
closed. Restore/replacement and a mismatched key or identity invalidate cached
data; stale or superseded refresh work cannot write or apply. This cache does not
change the Ready-snapshot requirement for new sends or existing outbox retry
behavior.

**Private membership (3-private-membership):** A private group roster is built
only from remotely listed, bounded, chat-key-authenticated and identity-signed
claims scoped to the exact chat ID. An accessible exact directory identity pair
strengthens display-name provenance; directory absence or inaccessibility does
not reject a valid chat-only claim. Conflicting identities fail closed. Private
opens never substitute the full community directory for this roster. Only verified
claims join the Ready recipient snapshot; attempts to publish, invites, history,
and local registration never infer invitees. The local self stays present. A
Keystore-encrypted, bounded cache is fenced by account/chat/key/identity/kind and
invalidated on replacement or restore. Pending self claims reuse the exact
ciphertext across retry; UI distinguishes not uploaded from uploaded and says
other members may not see the user yet. Private-only names are explicitly labelled.

**Verified participants (3-participants):** A separately accessible, labelled People
icon in the top bar (minimum 48×48dp target) opens a read-only list for the exact
active General, group, or DM graph; Invite/PersonAdd remains a separate action.
The list consumes the same `RecipientReadiness.Ready` snapshot and cache as
verified recipients and sending: member IDs and public-identity rows are
published together and agree semantically. For a DM, verified self and peer rows
are built before graph installation, so the first published graph already
contains the matching recipients and participant projection.

A valid Ready cache hit renders without waiting for network refresh; refresh
remains silent and a failed refresh does not displace the cached Ready state.
Without a valid cache, Loading shows progress and explanation while Feed remains
accessible; Unavailable offers the existing guarded roster retry. Rows expose
verified display names, a clear self marker, and only a short domain-separated
digest prefix of the public signing identity. Prefixes extend deterministically
to a bounded length and receive a stable disambiguator on collision; the full
digest stays internal to row keys, ordering, and collision checks, and is never
displayed. Self sorts first and peers sort deterministically by normalized name
then digest. No private keys, full/raw keys, credentials, box keys, or technical
blobs are displayed. Empty/self-only rosters remain truthful, and rows offer no
membership-management actions.

The saveable Participants destination supports system and toolbar Back to Feed;
a runtime-scope mismatch returns to Feed rather than displaying a stale graph.
This foreground-only view does not change background delivery.

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
Wire chat IDs and crypto are unchanged. At database open, legacy messages and
sync cursors are transactionally repaired into the sole distinct joined
community proven by the durable community registry and enumerated stored
connections (or, only when that registry is empty, a single stored legacy
connection with a nonblank joined-chat marker). Every enumerated connection must
decode and classify consistently; an unreadable or unclassifiable connection
fails repair closed. Current selection alone never establishes ownership. With
zero or multiple joined communities, legacy
history stays hidden and unassigned. A colliding message ID is preserved in the
legacy namespace while the already-owned row remains unchanged; colliding
cursors merge to the lexicographically later order token. Legacy rows carrying
an outbox envelope are never reassigned by this history repair. Repeated repair
is idempotent. Changing scope must not
display or send with stale state from the previous runtime. Credential-only
runtime rotation preserves Ready roster/member-name state so peer recipients
and change-index notifications remain intact; an in-flight Loading roster becomes
Unavailable with retry rather than remaining stuck on the replaced graph. Local
chat open/install synchronizes with account replacement, but is not held behind a
network poll. Group creation uses
the selected community's stored connection and runtime graph and opens the
created group in that community. Group creation and chat opening retain one
production request token issued at the Create/open tap. Group/DM roster
resolution after opening is asynchronous and applies only while its request
token, exact runtime graph, community, chat, and selection revision remain
current. Cancellation propagates; stale enrichment cannot alter the active
runtime. A superseding open converts abandoned Loading to retryable
Unavailable before the new request establishes its own readiness. A delayed
operation may mutate selection/runtime or install only while
its token is current and its
captured graph and selection revision remain valid; a later chat tap invalidates
earlier work before mutation and again at install after suspension. When group
creation intentionally activates a different community, it captures and validates
the new selected graph/revision; an unrelated intervening context change aborts
creation/opening. Cancellation of a public-group publication propagates instead
of being reported as success.
Credential rotation serializes the complete operation per owner, including remote
publication and local commit; independent owners may progress concurrently. The
snapshot is captured only after acquiring the owner lock. Network work never holds
the account-stability/replacement gate. The local update retains the captured
anchor and is committed only to that owner after account-generation and durable-
state validation; changing the selected community cannot redirect the write.
Inbound credential application uses the same owner lock. Runtime reinstallation
occurs only while the captured owner remains selected and its runtime key is
current. The active group/DM remains intact.

**Accessible retry control:** Failed-message retry is a labelled button with a
minimum 48×48dp target. It invokes the existing retry operation for the original
message; it does not change envelope or message-ID semantics.

**Related surfaces:** [Account recovery](account-recovery.md),
[Community settings](community-settings.md), and
[Background delivery](background-delivery.md).
