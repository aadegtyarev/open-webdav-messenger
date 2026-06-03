# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

(none active)

## Status

idle

## Done

- crypto feature complete: plan → architect (§5.1 + chat taxonomy + decision #9) → coder → plan-checker (approve) → code-review Pass 2 (5 findings fixed) → all gates green (JVM test/lint/ktlint + connectedAndroidTest 7/7 on device 5c3ff0). Committed + merged to main. Archived to archive/crypto-2026-06-03.md.

## Remaining

- Next feature — PM's choice. Candidates: message-model (plaintext envelope fields inside the ciphertext), X25519 (remote private chats), UI (Compose chat surface), or invite/onboarding. See .ai-pm/backlog.md.

## Touched files

(committed/merged)

## Next step

Wait for PM to describe the next feature.

## Validation

crypto: all four gates green, verified independently (connectedAndroidTest 7/7 on 5c3ff0).

## Notes

No git remote — merges are local squash-merges. Device 5c3ff0 (MIUI) re-gates "Install via USB" intermittently; needs the toggle on + a device-side confirm tap during connectedAndroidTest.
Two open architecture decisions remain (polling cadence / foreground-service; CI emulator — partially mooted since a real device works locally).
Backlog (.ai-pm/backlog.md) holds: X25519, directory (encrypted w/ community key), invite/onboarding (two-layer: community vs chat membership; re-key vs password-rotation removal), message-model, compression, UI, forward secrecy.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` and reset this one to a new task or to "Status: idle".
