# Execution state — ARCHIVED (chat-directory, 2026-06-04)

Snapshot of the completed chat-directory task. Archived on release; `current.md` reset to idle.

---

## Task

chat-directory: community chat directory on the shared WebDAV disk (sibling of §10 user directory) — signed, community-key-sealed chat descriptors {chat-id, kind, access, title}; discover joinable groups without a per-chat invite. Group-only (DMs excluded — PM scope decision); private-group existence/title discoverable within the community barrier, content key never in the directory. Backend substrate, no UI, no contract. Decision authority: autonomous (per-feature plan line).

## Status

done — released v0.7.0, PR #1 squash-merged to main (d358b42).

## Done

- Branch `feature/chat-directory` cut from main. Plan written (`docs/features/chat-directory_plan.md`).
- Security-relevant scope fork ESCALATED + answered by PM (2026-06-04, autonomous mode, via AskUserQuestion): list public + private GROUPS with {chat-id, kind, access, title}; DMs NEVER listed (social-graph privacy, hard-reject on publish + read); private-group content key never in the directory. Backend substrate → advocate exempt + no contract (directory precedent). Recorded in the plan's scope-decision note.
- pm-architect (pre-coding): arch note `.ai-pm/arch/chat-directory_arch.md` — thin `chatdirectory/` package on §10 primitives (Option 1c); supersede grouped by chat-id; §11 field order; no local cache.
- pm-coder: new `app/.../chatdirectory/` package (8 files). SupersedeResolver GENERALIZED (generic over T + caller grouping key) — all §10 tests stayed byte-green. webdav-layout.md §11 authored; §1–§10 byte-for-byte unchanged. 26 JVM tests + 2 instrumented.
- pm-architect (post-coding docs): architecture decision 13 + SC18/SC19 generalized + new SC20 (DMs never published / hard-reject on read) + Realized-by row; threat-model T23-T25 + two accepted-limitation non-goals. Dangling advocate-file citations corrected to the plan's scope-decision note.
- Review loop: pm-plan-checker pass 1 (approve, DoD pass, no blocking) → code-review pass 2 high effort (7 finder angles; correctness/refactor/security all clean; 1 reuse finding — hand-rolled hex → `protocol/Hex.encode`, fixed in b5eb91e; 3 lower findings dropped with rationale) → stamped `## Code review: 2026-06-04 — passed`.
- DEVICE GATE GREEN: `connectedDebugAndroidTest` ran on a real device (Xiaomi M2102J20SG / vayu, USB) — 24 instrumented tests, 0 failures. chat-directory device tests passed on the real ABI (`chat_directory_native_seal_sign_roundtrip`, `published_chat_entry_carries_only_public_key`). First feature whose connectedAndroidTest actually ran.
- Release: GitHub remote added (`git@github.com:aadegtyarev/open-webdav-messenger.git`, private); `main` pushed to seed the empty repo; pm-pr-prep bumped v0.6.0→v0.7.0 (versionCode 6→7) + CHANGELOG, pushed branch, opened PR #1; PM authorized merge; squash-merged to main (d358b42), remote + local feature branch deleted.

## Validation

Three JVM gates GREEN (`test`, `ktlintCheck`, `lint`) — full suite incl. 26 chat-directory tests + all §10 directory tests. `connectedDebugAndroidTest` GREEN on hardware (24 tests, 0 failures). Pass 1 approve + Pass 2 passed-stamp.

## Notes

- No git tag created for v0.7.0 (nor v0.6.0) — the project has no auto-tag CI (`.github/workflows/` absent; architecture.md "Release flow: N/A"). Only a stale `v0.5.0` tag exists. Tagging is outside the current release flow; offered to PM as a follow-up.
- Caller-tracks-version-counter (chat-directory, parallel to §10): the §11.5 supersede counter is supplied by the publish caller; persisting "last counter per chat-id" is the future UI/config feature's job (no local cache — arch Option 4A / Q4).
