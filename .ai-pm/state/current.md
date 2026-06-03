# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

Project bootstrap — initial documentation scaffold.

## Status

idle

## Done

- Project docs scaffolded (CLAUDE.md, README.md, docs/*)
- Stack and crypto model decided with PM
- Protocol template updated to v2.8.0; architecture.md / user-journeys.md skeletons refreshed
- pm-stack-researcher filled docs/stack-notes.md (core stack + compression + markdown); pipeline validators added to CLAUDE.md
- pm-architect authored docs/architecture.md (incl. Behavioral contract) + docs/product.md; 4 decisions recorded, 3 flagged OPEN for PM
- docs/product-map.md generated (greenfield — component groups, no contracts yet)
- Multi-user disk topology researched + decided: Topology A (one shared app-password per chat). Recorded by pm-architect in architecture.md (new decision #2) + product.md.

## Remaining

- PM to answer 3 open decisions (polling cadence, ktlint/detekt, CI emulator) — non-blocking, can defer
- Initial bootstrap commit (PM runs `git commit --no-verify` — no code exists yet)
- Plan the first feature (likely the WebDAV transport + docs/protocol/webdav-layout.md)

## Touched files

CLAUDE.md, README.md, docs/architecture.md, docs/product.md, docs/product-map.md, docs/stack-notes.md, docs/user-journeys.md, docs/threat-model.md, docs/ui-guide.md, .ai-pm/state/current.md, .gitmodules/.ai-pm/tooling (submodule bump to v2.8.0)

## Next step

PM validates the brief and answers the 3 open decisions; then make the bootstrap commit and wait for the first feature.

## Validation

pending

## Notes

Greenfield bootstrap. Stack: Kotlin + Jetpack Compose, WebDAV transport, passphrase-per-chat E2E. MVP scope: 1:1 + group chats, 5 reactions, replies, configurable polling. Deferred: Telegram gateway, multi-disk, X25519 identity keys.

Additional PM requirements (2026-06-03, to fold into architecture/product via pm-architect):
- Markdown in messages: bold, italic, inline+block code, quote, link (rendered client-side; markdown is part of the encrypted body).
- Traffic compression: compress message bodies/batches (gzip/zstd) BEFORE encryption — WebDAV is slow.
- Aggregated sync: one poll returns everything changed for the user (per-user inbox/changeset), NOT per-chat polling. Core protocol decision to minimise round-trips over slow WebDAV.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` and reset this one to a new task or to "Status: idle".
