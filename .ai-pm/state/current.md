# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

(none active)

## Status

idle

## Done

- sync feature complete: plan → architect (webdav-layout v2 rework: shared per-chat log + per-member change index + reserved window; arch note Variant A) → coder (sync/ + data/ modules, WorkManager poll, Room history, key caching) → pm-plan-checker pass 1 (approve after 2 interaction-test gaps fixed) → code-review pass 2 (7 findings: cursor contiguous-prefix no-loss/no-wedge fix #1, list-once #2, MKCOL hoist #3, codec reject-with-reason #4, message-id validation #5, HashTag/Hex reuse #6/#7 — all fixed + re-verified) → post-coding docs (architecture #3, threat-model T16-T19/SC17, first user-journey, §3 cursor note) → both passes clean → released v0.5.0 + CHANGELOG → squash-merged to main (900a7ff). Archived to archive/sync-2026-06-04.md.

## Remaining

- Next feature — PM's choice. Roadmap (.ai-pm/backlog.md): directory → invite/onboarding → rotation → community → compression → UI; then sync follow-ons (retention-window pruning, foreground-service fast-delivery mode, app-startup wiring of SyncRunner/SyncScheduler).

## Next step

Wait for PM: pick the next feature.

## Validation

sync: three JVM gates green (127 tests). connectedAndroidTest device-blocked (Room migration + native crypto pending an emulator — decision #8 open).

## Notes

No git remote — local squash-merges. Today 2026-06-04. Version baseline established: v0.5.0 (5 substrates), CHANGELOG.md created.
Done substrates: webdav-transport, crypto, identity, message-model, sync.
Deferred from sync (in backlog): retention-window pruning; foreground-service fast-delivery (resolves the still-open foreground side of decision #6); app-startup wiring (SyncRunner.install + SyncScheduler.schedule — waits on config/UI feature that supplies connection config + roster; SyncWorker runs a no-op runner until then); local DB encryption at rest (SC17/T16 — PM backlogged 2026-06-04).
Open: decision #6 foreground side; decision #7 (ktlint vs detekt); decision #8 (CI emulator for connectedAndroidTest — increasingly load-bearing: 5 substrates now have device-gated tests unrun).
Cardinality note (sync): selectPending does not scope log/ by chat-id (relies on AEAD key-mismatch); fine for one-chat-per-chat-root (§1); a future multi-chat-per-root layout needs a per-chat log scope — recorded in PollReader comments.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<date>.md` and reset this one to a new task or to "Status: idle".
