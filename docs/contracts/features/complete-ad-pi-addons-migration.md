# Complete ad-pi-addons migration

> Status: **approved**

## Goal

Make `AGENTS.md` the repository's sole normative source for agent guidance and complete the approved documentation migration without changing product behavior or technical content.

## Scenarios

- An agent starting work finds the project rules in `AGENTS.md`; `CLAUDE.md` only directs readers there.
- Chat's durable behavior contract remains unchanged at its canonical surface-contract path.
- Active docs/config references use current neutral terminology and canonical paths.

## Non-goals

No application, CI, Gradle, product guarantees, or technical-doc content changes. Do not remove feature/surface contract directories or push/create a PR.

## Affected surfaces

- [`../chat-surface.md`](../chat-surface.md), relocated to `../surfaces/chat-surface.md` without product-content changes.
- Repository agent guidance and documentation/config references (`AGENTS.md`, `CLAUDE.md`, `docs/stack-notes.md`, `docs/architecture.md`, `docs/threat-model.md`, `.editorconfig`, `.gitmodules`).

## Constraints

Keep scope limited to the approved migration. Preserve unrelated project instructions and durable contract substance. Stop for approval if the diff changes a surface, product guarantee, or acceptance scope. Remove this temporary feature contract after verification.

## Acceptance

- Tracked search finds no active `.ai-dev`, `.opencode`, `docs/features/`, or legacy process-role terminology (excluding historical CHANGELOG and ordinary product terms such as disk operator).
- All durable contracts live under `docs/contracts/surfaces/`.
- `CLAUDE.md` only redirects to `AGENTS.md`; references resolve; `git diff --check` passes.
- Final tree contains no temporary feature contract.

## Validation

Run tracked-content searches, verify links/contract locations, and run `git diff --check`. No Gradle tests: changes are documentation/configuration only.
