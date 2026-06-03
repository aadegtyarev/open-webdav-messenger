# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

(none active)

## Status

idle

## Done

- webdav-transport feature complete: plan → spec → code → plan-checker (approve) → code-review (Pass 2, 12 findings fixed) → committed dff0d2f on branch feature/webdav-transport. Archived to archive/webdav-transport-2026-06-03.md.

## Remaining

- PM decision: merge feature/webdav-transport into main (no git remote — local only).
- Manual smoke vs a real Yandex.Disk account (needs PM app-password).
- Next feature (suggested: crypto — AEAD + Argon2id, fills the envelope ciphertext slot).

## Touched files

(committed in dff0d2f)

## Next step

Wait for PM: merge to main and/or describe the next feature.

## Validation

Pipeline green (test/lint/ktlintCheck) on feature/webdav-transport, verified independently.

## Notes

No git remote configured — pr-prep/GitHub PR not applicable; merge happens locally if PM wants it.
Open product note: TLS is enforced — a non-loopback `http://` WebDAV URL is rejected (CleartextRejected); a self-hosted WebDAV server must be reachable over https.
Three open architecture decisions still pending (polling cadence / foreground-service, CI emulator). ktlint+Lint resolved. Codec-id reject-don't-guess gap (plan-checker note) is now closed in Envelope.read.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` and reset this one to a new task or to "Status: idle".
