# v0.23.1 Chat Migration and Navigation

- Status: **Approved**
- Date: 2026-10-08

## Goal

Deliver v0.23.1 to recover legitimate existing local history and make chat opening independent and reliable, without resetting user data or changing wire behavior.

## Scenarios

- Upgrade from 0.22.3 to 0.23.0 had made existing General history disappear; restore its visibility without clearing data.
- Existing group chat “Болталка” has never been opened; trying to open it must not prevent General from opening.
- Repair applies both during migration and to databases already migrated to schema v6.
- Open chats normally; a delay or failure opening one chat must not poison a later request for another. Install a delayed open only while its captured runtime and selection remain valid; stale failures must not block subsequent requests.
- Preserve community isolation and do not assign an owner when ownership is genuinely ambiguous.

## Non-goals

Protocol changes; destructive reset; fabricating ownership across genuinely ambiguous multiple communities; restoring cloud-deleted content; UI redesign.

## Affected surfaces

- [Chat surface](../surfaces/chat-surface.md)
- [Background delivery](../surfaces/background-delivery.md)

**Reconciliation required:** Existing legacy-unscoped language must be refined: deterministic ownership by a single community may be repaired, while genuinely ambiguous ownership remains hidden/unassigned.

## Constraints

- Preserve on-disk data; repair must be idempotent, including for already-schema-v6 databases.
- Do not claim crash-atomic behavior beyond what is verified.
- Retain community isolation; never guess between multiple plausible owners.
- Chat-open tokens are issued at tap dispatch and shared across General/group production opening. Group creation receives its token at Create dispatch and retains it through publication/open; obsolete work must be rejected before selection/revision/runtime mutation and again at install after suspension.
- Cancellation during public-group publication propagates; it must not be converted into successful creation.
- Cross-community group creation validates against the graph/revision captured after its intentional, token-guarded activation; an unrelated intervening runtime must block the stale open.
- A stale chat open may install only if its request token and captured runtime/selection are still valid; stale failure must not block later requests.

## Acceptance criteria

- Legitimate recoverable local history is visible after repair without data reset, including databases already at v6; ambiguous history remains unassigned and hidden.
- Opening a previously unopened group chat does not prevent opening General or another chat after delay/failure.
- Regression tests exercise actual Room migration/repair, same- and cross-community creation using production runtime graphs, stale-request/intervening-runtime rejection, cancellation propagation, and navigation/runtime opening.
- Changes agree with both linked surface contracts after required reconciliation.
- v0.23.1 is delivered.

## Validation

Run full Debug and Release tests, ktlint, Android lint, and androidTest compile/schema/migration checks. Obtain GPT-6.1 Sol review.
