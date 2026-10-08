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
- Roster/metadata enrichment may continue asynchronously after the local feed is
  visible. Its success updates enrichment; its failure leaves the feed usable.
- Rapid chat switching preserves production request-token/runtime selection
  ordering: stale work cannot install into or poison another chat or close its
  feed.
- If safe sending depends on pending or unverified recipient/key state, sending
  remains truthfully disabled/deferred or visibly pending until safety is
  established; no stale or unverified recipient may be used.

## Non-goals

- Guaranteeing network latency for synchronization or sending.
- Protocol changes, destructive cache reset, bypassing required key validation,
  or UI redesign.

## Affected surfaces

- [Chat surface](../surfaces/chat-surface.md) (exact affected surface).

## Interfaces and constraints

- Room remains the sole source of the displayed message feed/history.
- Opening the local feed must not await WebDAV roster, directory, or metadata.
- Preserve v0.23.1 production request-token/runtime-selection ordering.
- Background enrichment errors must not close the feed or mutate another chat.
- Preserve crypto/key validation and recipient ownership/verification rules.

## Acceptance criteria

- No chat-open path awaits WebDAV before showing the local feed; General, group,
  and DM opens have comparable local-history latency.
- A deterministic regression test suspends a remote read and proves local feed
  visibility before that read completes.
- Tests prove key/recipient safety while enrichment is pending, including
  truthful send disable/defer where verification is required.
- Tests cover enrichment success and failure, rapid switching, and isolation of
  feeds/state across chats.
- Release v0.23.2 passes Debug, Release, and full project gates. Review with
  GPT-6.1 Sol.

## Validation

Use deterministic latency tests with a suspended WebDAV read, plus key/recipient
safety, error, and rapid-switching tests. Run Debug and Release tests/builds and
all project gates, including ktlint and Android lint. Do not run these for this
contract-recording task.
