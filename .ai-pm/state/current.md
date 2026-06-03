# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

(none active)

## Status

idle

## Done

- identity feature complete: plan → architect (decision #10) → coder → plan-checker (approve) → code-review Pass 2 (core clean; 5 IdentityStore durability findings fixed) → all gates green (JVM + connectedAndroidTest 18/18 on device 5c3ff0). Committed + merged to main (062bdb8). Archived.

## Remaining

- Next feature — PM's choice. Recommended next: sync / message-model (message format + sync; reworks the retention model + decision #2 per the corrected "shared chat log + per-user index + retention window" design). See .ai-pm/backlog.md roadmap.

## Touched files

(committed/merged)

## Next step

Wait for PM to describe the next feature.

## Validation

identity: all four gates green, verified (JVM independent run + connectedAndroidTest 18/18 on 5c3ff0).

## Notes

No git remote — local squash-merges. Device 5c3ff0 (MIUI) intermittently re-gates "Install via USB".
Roadmap (.ai-pm/backlog.md): sync/message-model → directory → invite/onboarding → rotation → community → compression → UI; later TG gateway, forward secrecy, retention/TTL, Nextcloud Topology B.
CORRECTED retention model (supersedes "transient buffer"): disk = bounded retention window (shared encrypted per-chat log + per-user change index), full history local; to be designed in sync/message-model (architect reworks webdav-layout + decision #2).
Done substrates: webdav-transport, crypto (symmetric), identity (asymmetric). 3 open arch decisions: polling cadence/foreground-service; CI emulator (mooted locally); + retention rework pending.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` and reset this one to a new task or to "Status: idle".
