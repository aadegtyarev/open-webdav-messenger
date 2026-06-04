# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

chat-directory: community chat directory on the shared WebDAV disk (sibling of §10 user directory) — signed, community-key-sealed chat descriptors {chat-id, kind, access, title}; discover joinable groups without a per-chat invite. Group-only (DMs excluded — PM scope decision); private-group existence/title discoverable within the community barrier, content key never in the directory. Backend substrate, no UI, no contract. Decision authority: autonomous (per-feature plan line).

## Status

ready-to-ship

## Done

- Template bump: ai-pm-protocol v2.13.0 → v2.22.0 merged to main (867145c); no pending migrations; decision-authority mode stays interactive.
- directory feature complete: plan (user directory only; self-published + QR trust) → architect (4 choices: community-root + reserved `directory/`; §5 envelope + §8-style binary TLV inner; signed monotonic per-author version-counter grouped by signing-pubkey; no local cache) → coder (`app/.../directory/` publish + read/verify, webdav-layout §10 authored, +WebDavTransport.readContentAddressed) → pm-plan-checker pass 1 (approve, no blocking) → code-review pass 2 (no correctness/security findings; 1 cleanup — mapRead/mapContentAddressedRead duplication — fixed in 82d7ed8, re-verified) → post-coding docs (architecture decision 12 + SC18/SC19 + Realized-by; threat-model T20-T22) → both passes clean → released v0.6.0 + CHANGELOG → squash-merged to main (b535ee4). Archived to archive/directory-2026-06-04.md.
- chat-directory: branch `feature/chat-directory` cut from main. Plan written (`docs/features/chat-directory_plan.md`). Security-relevant scope fork ESCALATED + answered by PM (2026-06-04, autonomous mode): list public + private GROUPS with {chat-id, kind, access, title}; DMs NEVER listed (social-graph privacy, hard-reject on publish + read); private-group content key never in the directory. Stack-notes current (no researcher, no new validator). Backend substrate → advocate exempt + no contract (directory precedent).
- pm-architect (pre-coding): arch note written (`.ai-pm/arch/chat-directory_arch.md`) — 4 choices: thin `chatdirectory/` package on §10 primitives (Option 1c); supersede grouped by chat-id; §11 inner-descriptor field order; no local cache.
- pm-coder: IMPLEMENTED. New `app/.../chatdirectory/` package (8 files): ChatDirectoryService (publish→PublishOutcome / read→ChatDirectoryReadResult, never-throwing), ChatDirectoryCrypto (seal/open over §5 envelope), ChatDescriptorCodec + ChatDescriptorFormat (§11.3 TLV, reject-don't-guess parse, `dm`-hard-reject + invalid-access reject in parse path, no `!!`), ChatDirectoryPaths (content-addressed, SC16 gate, new reserved `chat-directory/` collection), ChatDescriptor (ChatKind/ChatAccess enums + verified ChatDirectoryEntry), ChatDirectoryResults (ChatPublishOutcome incl. RejectedDm), ChatDirectoryFactory. SupersedeResolver GENERALIZED (edge-level: `offer(groupingKey, value, …)` generic over T) — all §10 tests stayed byte-green, no copy needed. webdav-layout.md §11 authored (§11.1–§11.6 + Cross-references + trailer); §1–§10 byte-for-byte unchanged. 26 JVM tests (RoundTrip 6, Rejection 8, Interaction 7, StackSpec 5) + 2 instrumented. Pipeline: `test` + `ktlintCheck` + `lint` GREEN incl. all §10 directory tests still green.

## Remaining

- post-coding docs handoff: pm-architect authors architecture decision 13 (chat directory substrate; chat-id grouping; group-only/DM-excluded scope; no-cache; `app/.../chatdirectory/` module-map row) + threat-model T23-T25 + SCn (incl. the DM-never-published / hard-reject-on-read enforceable rule).
- review loop: pm-plan-checker pass 1 → code-review pass 2 → stamp.
- release: A/B/C ship gate (stays manual even in autonomous mode) — pending PM go.

## Next step

PM authorized device testing (USB). Awaiting PM ship decision (A: release now via pm-pr-prep v0.6.0→v0.7.0 + CHANGELOG + local squash-merge / B: hold on branch).

## Validation

Code-review pass-2 finding 1 FIXED: hand-rolled `%02x` hex → `protocol/Hex.encode()` at `ChatDirectoryService.kt:155` (chat-id grouping key) and `DirectoryService.kt:96` (signing-pubkey grouping key). `Hex.encode` is byte-identical (lowercase, two chars/byte, no separator), so both grouping keys stay byte-for-byte unchanged. The three lower findings stay consciously dropped (not changed). Re-ran the three JVM gates after the swap: BUILD SUCCESSFUL, full test suite green (tests recompiled + executed) — proof the swap is behavior-preserving.

DEVICE GATE NOW GREEN (decision 8 partially exercised). `./gradlew connectedDebugAndroidTest` ran on a real device (Xiaomi M2102J20SG / vayu, USB) — BUILD SUCCESSFUL, 24 instrumented tests, 0 failures / 0 errors. Includes the chat-directory device-gated tests: `chat_directory_native_seal_sign_roundtrip` (native lazysodium AEAD seal/open + Ed25519 sign/verify of a chat descriptor on the device ABI, no UnsatisfiedLinkError) and `published_chat_entry_carries_only_public_key` (Keystore-wrapped identity; only the public key is published). All prior substrates' device-gated tests passed in the same run. The native crypto + Keystore paths are now verified on hardware for this feature — the first feature whose connectedAndroidTest actually ran (decision 8 = CI emulator is still open as the *automated* gate, but the manual device run is green).

Three JVM gates GREEN: `./gradlew test`, `./gradlew ktlintCheck`, `./gradlew lint` — full suite incl. 26 new chat-directory tests and all §10 directory tests still green (SupersedeResolver generalization confirmed behavior-preserving). `connectedAndroidTest` is DEVICE-GATED and PENDING: `connectedDebugAndroidTest` → `DeviceException: No connected devices!` (decision 8 — CI emulator — still open). Native AEAD/Ed25519 chat-descriptor paths gate on a device.

## Notes

No git remote — local squash-merges. Today 2026-06-04. Version baseline now v0.6.0 (6 substrates), CHANGELOG.md current.
Done substrates: webdav-transport, crypto, identity, message-model, sync, directory.
Directory deferred (in backlog / out of scope): chat directory (separate plan); QR safety-number + fingerprint display (UI); onboarding key distribution; remote-private-chat DH wiring; host-attested entries + meta/community.json owner marker (community feature); key rotation / member removal; roster file management; local Room cache of directory entries (UI feature, arch Option 4A).
Open: decision #6 foreground side; decision #7 (ktlint vs detekt); decision #8 (CI emulator for connectedAndroidTest — 7 substrates now have device-gated tests).

Touched files (chat-directory): NEW main — `app/src/main/kotlin/org/openwebdav/messenger/chatdirectory/{ChatDirectoryService,ChatDirectoryCrypto,ChatDescriptorCodec,ChatDescriptorFormat,ChatDirectoryPaths,ChatDescriptor,ChatDirectoryResults,ChatDirectoryFactory}.kt`. NEW test — `app/src/test/.../chatdirectory/{ChatDirectoryTestSupport,ChatDirectoryFakeDisk,ChatDirectoryRoundTripTest,ChatDirectoryRejectionTest,ChatDirectoryInteractionTest,ChatDirectoryStackSpecTest}.kt`; NEW androidTest — `.../chatdirectory/ChatDirectoryInstrumentedTest.kt`. EDITED main — `app/src/main/.../directory/SupersedeResolver.kt` (edge-level generic-over-T grouping-key generalization) + `directory/DirectoryService.kt` (passes signing-pubkey hex as grouping key — §10 behavior unchanged). EDITED doc — `docs/protocol/webdav-layout.md` (§11 added; §1–§10 untouched). NOT touched: `docs/architecture.md`, `docs/threat-model.md` (post-coding pm-architect handoff).
Chat-directory caller-tracks-version-counter (parallel to §10): the §11.5 supersede counter is supplied by the publish caller; persisting "last counter per chat-id" is the future UI/config feature's job (no local cache landed — arch Option 4A / Q4).
Caller-tracks-version-counter note (directory): the §10.5 supersede version-counter is supplied by the caller (publish API); persisting "last counter used per identity" is the future config/UI feature's responsibility (no local cache landed this feature, arch Option 4A).
