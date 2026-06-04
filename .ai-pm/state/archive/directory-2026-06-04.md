# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

directory — community user directory on the shared WebDAV disk: members publish signed, community-key-encrypted identity entries; members list + decrypt + verify to discover peers and their verified public keys. Backend substrate (no UI). User-directory only (chat directory deferred); self-published + QR-verify trust model.

## Status

review — Pass 1 approved; Pass 2 code-review fix landed (1 cleanup finding)

## Done

- Template bump: ai-pm-protocol v2.13.0 → v2.22.0 merged to main (867145c); no pending migrations; mode stays interactive.
- Plan drafted + PM-approved: `docs/features/directory_plan.md`. PM decisions — scope: user directory only (chat directory = separate plan); trust: self-published Ed25519-signed entries + QR safety-number (host-attested rejected for MVP). Community key = community-wide symmetric AEAD key, supplied as config (onboarding distributes it later), never the disk credential, never on disk.
- pm-architect: structural pass — `.ai-pm/arch/directory_arch.md` resolved the 4 choices (1A community-root + reserved `directory/`; 2A §5 envelope + §8-style binary versioned TLV inner; 3A signed monotonic per-author version-counter grouped by Ed25519 signing-pubkey, content-hash tiebreak; 4A NO local cache this feature).
- pm-coder: authored `docs/protocol/webdav-layout.md` §10 (community user directory — §1–§9 byte-for-byte unchanged; status/cross-refs/footer updated). Commit 8f31c8f.
- pm-coder: implemented `app/.../directory/` (DirectoryFormat, DirectoryEntry, DirectoryResults, DirectoryEntryCodec, DirectoryPaths, DirectoryCrypto, SupersedeResolver, DirectoryService, DirectoryFactory) + added `WebDavTransport.readContentAddressed` for the §10.4 content-addressed name. Reuses crypto/MessageCrypto + identity/IdentityCrypto + transport verbs verbatim; no new dependency.
- pm-coder: full Test plan — JVM round-trip/rejection/interaction/stack-spec suites + the 2 connectedAndroidTest cases. Pipeline green (see Validation).
- Review Pass 1 (pm-plan-checker): APPROVE; DoD pass; no blocking. `.ai-pm/reviews/directory_review.md`.
- Review Pass 2 (code-review, high): no correctness/security bugs; 1 cleanup finding (duplicated GET read paths). pm-coder fixed it — extracted `WebDavTransport.mapVerifiedRead(response, hashOk)` so `mapRead`/`mapContentAddressedRead` share the 404/readCapped/frame-parse flow, differing only in the hash predicate. Behavior byte-identical; existing tests unchanged + green. Commit 82d7ed8. Pipeline re-run GREEN (test + ktlintCheck + lint).

## Remaining

- Post-coding docs handoff: pm-architect updates `docs/architecture.md` (new decision "Directory substrate" + flip `app/.../directory/` to Implemented in the module map; record community key as fourth-family symmetric AEAD key, self-published Ed25519 entries, §10.1 on-disk home, §10.5 supersede choice, §10.6 disk-recompute/no-cache) and `docs/threat-model.md` (new Tnn/SCn for impersonation-by-name accepted-limitation, community-key-compromise community-barrier tier, directory metadata exposure). NOTE: the plan's optional "Local cache" contract is DEFERRED (arch Option 4A), not instantiated — flag for pm-plan-checker.
- Re-run code-review (Pass 2) to confirm the cleanup finding is cleared; orchestrator stamps `## Code review: <date> — passed` in the review file.
- Ship: PR/merge (local — no remote).

## Next step

orchestrator re-runs code-review to verify Pass 2 clean, then ship.

## Validation

Pipeline run 2026-06-04 — all three JVM gates GREEN:
- `./gradlew test` — GREEN (full suite incl. new directory JVM tests).
- `./gradlew ktlintCheck` — GREEN.
- `./gradlew lint` — GREEN.
- `./gradlew connectedAndroidTest` (DirectoryInstrumentedTest) — GREEN on a connected device (M2102J20SG / Android 12): native lazysodium-android AEAD seal/open + Ed25519 sign/verify of a directory entry, no UnsatisfiedLinkError. (Device was available this run; decision 8 CI emulator still open.)

## Notes

No git remote — local squash-merges. Today 2026-06-04. On branch feature/directory.
Touched files (this coding pass): docs/protocol/webdav-layout.md (§10 authored); app/src/main/kotlin/org/openwebdav/messenger/directory/{DirectoryFormat,DirectoryEntry,DirectoryResults,DirectoryEntryCodec,DirectoryPaths,DirectoryCrypto,SupersedeResolver,DirectoryService,DirectoryFactory}.kt; app/src/main/kotlin/org/openwebdav/messenger/transport/WebDavTransport.kt (added readContentAddressed); app/src/test/kotlin/org/openwebdav/messenger/directory/{DirectoryTestSupport,DirectoryFakeDisk,DirectoryRoundTripTest,DirectoryRejectionTest,DirectoryInteractionTest,DirectoryStackSpecTest}.kt; app/src/androidTest/kotlin/org/openwebdav/messenger/directory/DirectoryInstrumentedTest.kt. Commits: 8f31c8f (spec §10), feat directory impl, test directory.
No Product Contract — backend substrate, no user-visible UI surface (UI is a later feature); same precedent as the 5 prior substrates. pm-product-advocate gate does not fire (not user-facing).
Security-bearing: threat-model.md update is REQUIRED (Cryptography/key-mgmt, Data-at-rest, Network/transport, PII surfaces) — in plan's Docs to update, owner pm-architect post-coding.
Done substrates: webdav-transport, crypto, identity, message-model, sync. This adds: directory.
