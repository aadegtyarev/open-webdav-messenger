# v0.24.0 — Verified participant list

- **Status:** Complete
- **Approved:** 2026-10-08
- **Release:** v0.24.0 (code 58)

## Goal

Expose a read-only, verified participant list for the exact currently open General, group, or DM chat. Reuse the v0.23.3 verified-recipient readiness/cache as its source; do not introduce a separate roster source or block the feed on network work.

## Scenarios and behavior

- An explicit top-bar Participants control, distinct from PersonAdd/Invite, opens the list. It has a clear content description and a target of at least 48dp. Follow the existing `Screen`/`rememberSaveable` navigation model with a dedicated destination: system Back and toolbar Back return to the exact feed, and destination restoration survives activity recreation.
- Show only members for the exact active community/chat. Each entry shows a verified display name, a short non-sensitive disambiguator derived from the public signing identity, and an unambiguous “You” marker for the current identity. Ordering is deterministic and self placement is clear. Never expose private keys, full raw keys, credentials, or technical blobs.
- Ready/cache hit renders immediately without waiting for network. With no cache, Loading shows progress and an explanation while the feed remains accessible. Unavailable shows an error and Retry using the existing roster refresh. A valid cached list remains stable and silent during refresh; refresh failure after cache application does not replace it with an error. Results from a stale request or switched chat must never appear in this list.
- Empty and self-only rosters, including DM state, are represented truthfully. Invite remains a separate action; list rows do not imply membership management.

## Non-goals

No membership management, delete, ban, role changes, protocol changes, new roster/cache/readiness ownership, or change to delivery/send readiness. Release v0.24.0 and pause for user device validation before separately contracting v0.25.0 removal/revocation.

## Affected surfaces

- [Chat surface](../surfaces/chat-surface.md): participant-list UI consuming existing verified roster readiness/cache.
- Background delivery is not affected: this is a read-only foreground consumer and does not change readiness or delivery behavior.

## Interfaces and constraints

- Reuse the current exact-chat runtime roster, readiness states, cache behavior, and existing refresh/retry operation. A Ready cache hit must not wait on network.
- Use the existing saveable `Screen` navigation pattern, which already supports system Back; provide toolbar Back as well. Keep feed available during loading and failure.
- No misleading or actionable membership controls. Invitation remains distinct.

## Acceptance and validation

- Tests cover General, group, and DM lists; cached and process-reopen state; Loading, Unavailable/retry, refresh stability, stale results/chat switches; Back/navigation/recreation; accessibility labels and target size; empty/self-only truthfulness; and no network wait when cache is Ready.
- Final verification passed: Debug unit tests (445, 0 failures/errors, 1 skipped), Release unit tests (409, 0 failures/errors), `ktlintCheck`, `lint`, `compileDebugAndroidTestKotlin`, `assembleDebug`, and `assembleRelease`. Debug and release APKs were assembled; publication is not claimed.
- Sol review: APPROVED after the final DM first-publication atomicity fix (`c74bc5d`). The production-path observer test verifies the first published DM Ready snapshot has matching self+peer members and participant rows, with no later repair emission.
- User device validation remains the next release follow-up. Do not begin v0.25.0 removal/revocation before that validation and its separate contract.
