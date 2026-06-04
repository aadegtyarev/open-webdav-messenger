# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

(none active)

## Status

idle

## Done

- Template bump: ai-pm-protocol v2.13.0 → v2.22.0 merged to main (867145c); no pending migrations; decision-authority mode stays interactive.
- directory feature complete: plan (user directory only; self-published + QR trust) → architect (4 choices: community-root + reserved `directory/`; §5 envelope + §8-style binary TLV inner; signed monotonic per-author version-counter grouped by signing-pubkey; no local cache) → coder (`app/.../directory/` publish + read/verify, webdav-layout §10 authored, +WebDavTransport.readContentAddressed) → pm-plan-checker pass 1 (approve, no blocking) → code-review pass 2 (no correctness/security findings; 1 cleanup — mapRead/mapContentAddressedRead duplication — fixed in 82d7ed8, re-verified) → post-coding docs (architecture decision 12 + SC18/SC19 + Realized-by; threat-model T20-T22) → both passes clean → released v0.6.0 + CHANGELOG → squash-merged to main (b535ee4). Archived to archive/directory-2026-06-04.md.

## Remaining

- Next feature — PM's choice. Roadmap (.ai-pm/backlog.md): chat directory (sibling of this feature) → invite/onboarding (distributes the community key + chat keys) → rotation → community (host-governed: meta/community.json owner marker, polling floor) → compression → UI; plus sync follow-ons (retention-window pruning, foreground-service fast-delivery, app-startup wiring) and local-DB-at-rest encryption (SC17/T16).

## Next step

Wait for PM: pick the next feature.

## Validation

directory: three JVM gates green (full suite incl. 21 new directory tests) + connectedAndroidTest green on a device this run (native AEAD seal/open + Ed25519 sign/verify). Decision 8 (CI emulator) still open.

## Notes

No git remote — local squash-merges. Today 2026-06-04. Version baseline now v0.6.0 (6 substrates), CHANGELOG.md current.
Done substrates: webdav-transport, crypto, identity, message-model, sync, directory.
Directory deferred (in backlog / out of scope): chat directory (separate plan); QR safety-number + fingerprint display (UI); onboarding key distribution; remote-private-chat DH wiring; host-attested entries + meta/community.json owner marker (community feature); key rotation / member removal; roster file management; local Room cache of directory entries (UI feature, arch Option 4A).
Open: decision #6 foreground side; decision #7 (ktlint vs detekt); decision #8 (CI emulator for connectedAndroidTest — 6 substrates now have device-gated tests).
Caller-tracks-version-counter note (directory): the §10.5 supersede version-counter is supplied by the caller (publish API); persisting "last counter used per identity" is the future config/UI feature's responsibility (no local cache landed this feature, arch Option 4A).
