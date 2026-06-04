# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

(none active)

## Status

idle

## Done

- Template bump: ai-pm-protocol v2.13.0 → v2.22.0 → v2.23.0 merged to main; no pending migrations; decision-authority project default stays interactive.
- chat-directory feature complete: plan (group-only chat directory; DMs excluded — escalated PM scope decision; autonomous per-feature authority) → pre-coding arch note (thin `chatdirectory/` package on §10 primitives; supersede by chat-id; no cache) → coder (`app/.../chatdirectory/` 8 files + webdav-layout §11 + SupersedeResolver generalized; 26 JVM + 2 instrumented tests) → post-coding docs (architecture decision 13 + SC18/SC19 generalized + SC20; threat-model T23-T25) → pm-plan-checker pass 1 (approve) → code-review pass 2 (1 reuse finding fixed, 3 dropped; passed) → device gate GREEN on Xiaomi M2102J20SG (24 instrumented tests, 0 failures) → released v0.7.0 + CHANGELOG → PR #1 squash-merged to main (d358b42). Archived to archive/chat-directory-2026-06-04.md.

## Remaining

- Next feature — PM's choice. Roadmap (`.ai-pm/backlog.md`): invite/onboarding (distributes the community key + chat keys; two-layer community vs chat membership) → rotation (rotate-with-auto-replace, member removal) → community (host-governed: meta/community.json owner marker, polling floor) → compression → UI; plus sync follow-ons (retention-window pruning, foreground-service fast-delivery, app-startup wiring) and local-DB-at-rest encryption (SC17/T16).

## Next step

Wait for PM: pick the next feature.

## Validation

chat-directory: three JVM gates green (full suite incl. 26 chat-directory + all §10 directory tests) + connectedAndroidTest GREEN on a real device this run (24 instrumented tests, 0 failures — native AEAD seal/open + Ed25519 sign/verify + Keystore). Decision 8 (CI emulator as the *automated* gate) still open; the manual device run is green.

## Notes

GitHub remote now configured: `git@github.com:aadegtyarev/open-webdav-messenger.git` (private). `main` pushed; PRs flow via `gh` (pm-pr-prep). Today 2026-06-04. Version baseline now v0.7.0 (7 substrates), CHANGELOG.md current.
Done substrates: webdav-transport, crypto, identity, message-model, sync, directory, chat-directory.
No git tags for v0.6.0 / v0.7.0 — project has no auto-tag CI (`.github/workflows/` absent; architecture.md "Release flow: N/A"). Stale `v0.5.0` tag exists. Tagging/CI is a deferred decision (offered to PM).
Open: decision #6 foreground side; decision #7 (ktlint vs detekt); decision #8 (CI emulator for connectedAndroidTest — 7 substrates have device-gated tests; chat-directory's ran green on hardware this release).
Chat-directory deferred (backlog / out of scope): invite/onboarding key distribution (community key + private-chat keys); DMs in directory (excluded — social-graph privacy); authoritative chat-ownership marker / host-attested chat entries; chat-id grammar pinning; UI (chat-list surface, join button); local Room cache of chat-directory entries (UI feature); per-member revocation / re-key (rotation feature).
