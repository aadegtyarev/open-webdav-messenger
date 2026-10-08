# v0.23.2 Uniform Chat-Open Latency

- Status: **Approved**
- Date: 2026-10-08
- Target release: v0.23.2

## Goal

Every chat type (General, group, and DM) opens with comparable latency by
showing its locally stored Room history/feed without waiting for WebDAV roster,
directory, or metadata reads. In v0.23.1 history is restored; ADFamily General
opens quickly, while ADFamily Болталка now works (messages and history) but can
take 1–2 minutes to open. Remove that type-dependent delay without weakening
recipient or cryptographic safety.

## Scenarios

- First and repeated opens render available local history promptly for General,
  group, and DM chats, including with a slow or unavailable network.
- The Room-backed Feed appears immediately, while verified roster loading
  continues asynchronously. A group/DM top bar shows an indeterminate
  connecting spinner during Loading. Send is disabled during Loading and
  Unavailable; General may be Ready immediately when its existing verified
  recipient/key state is sufficient.
- When roster verification succeeds, the matching chat becomes Ready and Send
  is enabled. On failure, the Feed/history/navigation remain usable, Send stays
  disabled, and a compact truthful status provides the supported retry or
  reconnect route without introducing a new UI design.
- Rapid chat switching preserves production request-token/runtime selection
  ordering. Background success applies only to the same request token, graph,
  community, chat, and selection revision; stale results and errors are
  discarded and cannot poison another chat or close its feed.
- No envelope or retry payload is built from an incomplete roster, and no stale
  or unverified recipient/key may be used.

## Non-goals

- Guaranteeing network latency for synchronization or sending.
- Protocol changes, destructive cache reset, bypassing required key validation,
  or UI redesign.

## Affected surfaces

- [Chat surface](../surfaces/chat-surface.md).
- [Background delivery](../surfaces/background-delivery.md).

## Interfaces and constraints

- Room remains the sole source of the displayed message feed/history.
- Opening the local feed must not await WebDAV roster, directory, or metadata.
- Preserve v0.23.1 production request-token/runtime-selection ordering.
- Background enrichment errors must not close the feed or mutate another chat.
- Preserve crypto/key validation and recipient ownership/verification rules.
- Represent recipient readiness as Loading, Ready, or Unavailable. Group/DM
  readiness requires verified roster data; do not enable Send or build an
  envelope/retry payload before that requirement is met. General may be Ready
  immediately only when its existing verified recipient/key state is sufficient.
- Show indeterminate top-bar progress for group/DM roster Loading and disable
  Send for Loading/Unavailable. Expose progress and disabled state accessibly.
  On Unavailable, use only compact truthful status and an existing supported
  retry/reconnect route; do not invent a broader UI.
- Apply successful background results only when request token, graph,
  community, chat, and selection revision still match. Discard stale results
  and errors; failure leaves Feed/history/navigation available.

## Acceptance criteria

- No chat-open path awaits WebDAV before showing the local feed; General, group,
  and DM opens have comparable local-history latency.
- A deterministic test suspends the remote roster reader and proves the Room
  Feed is visible, group/DM progress is exposed, and Send is disabled before
  that reader completes.
- Verified roster success enables Send only for the still-current request
  token, graph, community, chat, and selection revision. Failure and stale
  success/error cannot enable Send or poison another chat; failure preserves
  Feed/history/navigation and offers the truthful supported retry/reconnect
  route.
- Tests prove no envelope or retry payload is built from an incomplete roster
  and cover General readiness, rapid switching, and isolation across chats.
- Accessibility checks verify progress semantics and that disabled Send is
  exposed as disabled.
- Release v0.23.2 passes Debug, Release, and full project gates. Review with
  GPT-6.1 Sol.

## Validation

Use deterministic latency tests with a suspended WebDAV read, plus key/recipient
safety, error, and rapid-switching tests. Run Debug and Release tests/builds and
all project gates, including ktlint and Android lint. Do not run these for this
contract-recording task.
