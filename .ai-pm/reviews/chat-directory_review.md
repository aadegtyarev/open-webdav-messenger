# chat-directory — plan-compliance review (Pass 1)

Backend substrate; `Decision authority: autonomous` (per-feature plan line). Verified against `docs/features/chat-directory_plan.md`, `.ai-pm/arch/chat-directory_arch.md`, and the branch `feature/chat-directory` diff.

## Plan compliance

### Scenarios (all 7 implemented + tested)

- ✓ **S1 Publish a chat descriptor** — `ChatDirectoryService.publishChatEntry` (sign → seal → content-address → idempotent MKCOL + PUT); `dm` rejected before any write (`ChatPublishOutcome.RejectedDm`, returned at the top of the method). Test: `ChatDirectoryRoundTripTest.publish_then_read_roundtrips_verified_chat_entry`, `dm_kind_rejected_on_publish`.
- ✓ **S2 Read + verify** — `readChatDirectory` (Depth-1 list → per-entry content-hash/open/verify → resolve latest per chat-id); every failure a typed drop. Tests: round-trip + the full `ChatDirectoryRejectionTest` suite.
- ✓ **S3 Discovery feeds downstream (not wired)** — `ChatDirectoryEntry {chatId, kind, access, title, publishedBySigningKey}` surfaced; no join/key-fetch/render (correctly absent).
- ✓ **S4 Update / supersede per chat-id** — `SupersedeResolver` grouped by chat-id hex. Tests: `updated_chat_entry_supersedes_older`, `updated_descriptor_resolves_latest_per_chat_id`.
- ✓ **S5 Unreadable without community key** — `read_without_community_key_yields_nothing_readable`; `chat_entry_is_ciphertext_only_on_disk` proves no cleartext chat-id/title/kind/pubkey on disk.
- ✓ **S6 DMs + private-chat keys never exposed** — `dm` hard-reject at publish AND read; private group surfaces existence+title, no content key. Tests: `dm_kind_entry_dropped_on_read`, `private_group_listed_without_key`.
- ✓ **S7 Robustness / flat-trust degradation** — typed drops, no wedge, transient retry-next-cycle (`NotReady`). Tests: `tampered_chat_entry_does_not_wedge_directory`, `malformed_or_truncated_chat_entry_rejected`.

### Contracts

- ✓ `publishChatEntry(...) → ChatPublishOutcome` (Published/RejectedDm/Failed), never throws, idempotent on identical bytes.
- ✓ `readChatDirectory(communityKey) → ChatDirectoryReadResult` (entries latest-per-chat-id + rejectedCount + listingFailed), never throws.
- ✓ `ChatDirectoryEntry` carries the verified record incl. `publishedBySigningKey(32)`; no content key. `chatId` opaque/length-prefixed.
- ✓ `kind ∈ {dm, group}` / `access ∈ {public, private}` — only `group` valid; `dm` and out-of-enum `access` are typed rejections (`ChatDescriptorCodec.parse`).

### Categorical coverage (kind × access taxonomy)

- ✓ `kind`: `group` covered; `dm` sibling explicitly excluded with a one-line reason under Out of scope (PM scope decision) AND enforced (publish + read hard-reject).
- ✓ `access`: both `public` and `private` covered; out-of-enum rejected.

### Interaction scenarios (each has a concurrent/post-condition test)

- ✓ Concurrent same-chat publishes both land — `concurrent_publishes_same_chat_both_land` (two authors, two content-addressed files, latest wins).
- ✓ Publish during read picked up next read — `publish_during_read_picked_up_next_read`.
- ✓ Tampered/deleted doesn't wedge — `tampered_chat_entry_does_not_wedge_directory`.
- ✓ Forged `dm` dropped, others read — `forged_dm_entry_dropped_others_read`.
- ✓ Supersede latest per chat-id — `updated_descriptor_resolves_latest_per_chat_id`.
- ✓ Cross-collection no-touch — `chat_directory_read_does_not_touch_user_directory_or_chat_folders` (asserts §10 `directory/`, chat `log/`/`changes/`/`meta/` untouched against the real protocol paths).
- ✓ Wrong/stale community key dropped — `wrong_key_chat_entry_dropped_not_crash`.

### Stack expectations (each cited rule has a verifying stack-spec test with source URL)

- ✓ Ed25519 hard-reject on `-1` — `chat_entry_signature_hard_rejects_on_failure` (flips sig byte + all-zero sig → BAD_SIGNATURE; cites public-key-signatures URL). Not a self-consistent round-trip.
- ✓ 24-byte AEAD nonce, fresh per seal — `chat_entry_aead_uses_24_byte_random_nonce` (asserts `AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES == 24` and two seals → distinct nonce bytes; cites AEAD URL + §5.1).
- ✓ Community key ≠ disk credential, never on disk — `community_key_is_not_the_disk_credential` (cites SC3/SC19).
- ✓ SC16 path-traversal — `chat_directory_path_rejects_traversal` (`../`, `/`, uppercase, wrong length, spaces all rejected; cites SC16 + §0/§11.4).
- ✓ PROPFIND Depth 1 — `chat_directory_propfind_depth_is_one` (asserts every PROPFIND uses Depth 1, none infinity; cites RFC 4918 + §6/§11.6).

### Architecture compliance (4 structural choices)

- ✓ Thin `chatdirectory/` package (8 main files) over shared primitives — Option 1c.
- ✓ `SupersedeResolver` **generalized** (generic over `T`, caller-supplied grouping key) — not copied; the only §10 source edits are `SupersedeResolver.kt` + a pure grouping-key passthrough in `DirectoryService.kt` (supplies the same signing-pubkey hex the old resolver extracted internally). All §10 `directory` tests stay green — the green-§10-tests path of the green-or-copy rule was satisfied.
- ✓ Supersede grouped by chat-id (not signing-pubkey).
- ✓ §11.3 field order matches the arch (Q3): `chat-entry-version ‖ signing-pubkey(32) ‖ version-counter(u64) ‖ kind ‖ access ‖ chat-id-len+chat-id ‖ title-len+title ‖ signature(64)`; signing-pubkey inside the signed range; reject-don't-guess parse, no `!!`.
- ✓ No local cache (disk-recompute read per call).

### Protocol doc §11 (additive only)

- ✓ `git diff` confirms `docs/protocol/webdav-layout.md` is purely additive: one insertion hunk (§11 block) + the Cross-references/trailer hunk. §1–§10 are byte-for-byte unchanged.

### Docs to update (all landed — DoD item 8)

- ✓ `webdav-layout.md` §11 authored (§11.1–§11.6 + cross-ref + trailer note).
- ✓ `docs/architecture.md` decision 13 + SC18/SC19 generalized to both directories + new SC20 (DMs never published / `dm` hard-reject on read) + `chat-directory` Realized-by row + `app/.../chatdirectory/` module-map row + threat→constraint wiring.
- ✓ `docs/threat-model.md` T23 (chat-descriptor spoofing / unauthorized supersede — accepted limitation), T24 (private-group existence/title within the community barrier; content key never in directory), T25 (chat-directory metadata to the disk operator — A5 class), each with attacker/likelihood/impact/mitigation + matching non-goal entries. Not skeletal. `Last reviewed: 2026-06-04` matches the feature date.

### Product Contract

No Product Contract / advocate artifact — **correct**. This is a backend substrate with no human-role scenario subject (the "a member" framing is illustrative; no user-visible surface ships). Same exemption as the §10 directory feature. The escalated group-only/DM-excluded scope decision is recorded in the plan's scope-decision note (the proper home for an autonomous-mode escalation), not a missing advocate file. No missing-artifact gap.

## Definition of Done

- [x] All plan scenarios implemented and tested (7/7 + contracts + categorical coverage)
- [x] Interaction scenarios have concurrent-state tests (7/7)
- [x] Stack expectations respected; stack-spec tests pass (5/5, verify the cited rule with source URLs)
- [x] Product Contract honored; Acceptance checks pass; no silent behavior change — **n/a, no Product Contract touched** (backend substrate, §10 precedent; coder's commit carries the skip reason)
- [x] Pipeline green — `./gradlew test ktlintCheck lint` BUILD SUCCESSFUL (re-run this review); `connectedAndroidTest` device-gated/PENDING (`DeviceException: No connected devices!`, decision 8 open) — the honest, accepted state of every prior substrate
- [x] State file updated (`.ai-pm/state/current.md`, Status: review)
- [x] Product Impact Report present — **n/a** (no user-facing contract touched)
- [x] Docs updates landed (§11, architecture decision 13 + SC20, threat-model T23–T25)
- [x] Expected artifacts exist (plan, this review; no contract required — not user-facing)
- [n/a] Product-readiness gate (user-facing only) — feature is backend substrate, no human-role scenario subject; advocate exempt
- [n/a] Validation gate (documentation-kind only) — `software` project

**DoD: pass**

## Blocking

None.

## Notes (product)

None requiring a PM decision. (For the orchestrator's awareness, not a PM-facing fork: `connectedAndroidTest` remains device-gated and unrun — it gates the native AEAD/Ed25519 chat-descriptor paths on a real device ABI. This is the same open decision-8 state as all 6 prior substrates; the instrumented tests compile and are wired, so they run the moment a device/emulator is available. Not a blocking gap for this feature — consistent with established project precedent.)

## Verdict

approve

<!-- The trail below is the ONE review section the orchestrator owns, not pm-plan-checker. -->
## Code review findings

Pass 2 — `code-review` high effort (7 finder angles: line-by-line correctness, removed-behavior/refactor, security/privacy, reuse, simplification, efficiency, altitude), 1-vote verify. Correctness, the §10 SupersedeResolver refactor, and all security/privacy gates verified clean (DM hard-reject at publish AND read, Ed25519 hard-reject on `-1`, content-hash-on-read, signing-secret `fill(0)` wipe, AEAD AAD header binding, SC16 name gate, non-`none` codec dropped; `§1–§10` byte-for-byte unchanged). One actionable cleanup finding confirmed; three lower findings considered and dropped with rationale (recorded for the trail).

### Finding 1 (CONFIRMED — reuse miss, fix) — hand-rolled hex instead of the existing `protocol/Hex.encode()`

- `app/src/main/kotlin/org/openwebdav/messenger/chatdirectory/ChatDirectoryService.kt:155` — `chatIdHex = d.chatId.joinToString("") { "%02x".format(it) }` re-rolls the exact nibble loop that `protocol/Hex.encode()` already provides (the helper was extracted by a prior review finding 7; already used by `data/MessageStore`).
- `app/src/main/kotlin/org/openwebdav/messenger/directory/DirectoryService.kt:96` — the same hand-rolled `joinToString("") { "%02x".format(it) }` on the grouping-key derivation line **moved here by this PR's SupersedeResolver refactor** (so it is in-diff). `Hex.encode` is byte-identical (lowercase, two chars/byte, no separator), so replacing it keeps the §10 grouping key byte-for-byte unchanged.
- **Fix:** replace both call sites with `Hex.encode(...)`. Low risk, behavior-preserving; all §10 + chat-directory tests must stay green.

### Considered and dropped (recorded — not fixed)

- **Cursor + big-endian writer duplication** (`ChatDescriptorCodec` ↔ §10 `DirectoryEntryCodec`, ~48 lines of generic binary read/write primitives) — DROPPED. The pre-coding arch note (`.ai-pm/arch/chat-directory_arch.md`, Option 1c) **consciously chose two thin per-directory codecs** sharing only the byte-level seal/open/sign/verify + path minting + supersede resolver. Extracting these primitives to a new shared `protocol/` class would edit §10's done `DirectoryEntryCodec` for ~48 lines — more blast radius than value on a backend substrate. Accepted as an arch-decision consequence, not a defect.
- **Generic enum-codec helper** for `ChatAccess` (byte↔enum `when`) — DROPPED. Over-engineering for a single closed 2-value enum; the explicit `when` with a `null`→typed-reject is clearer and is the reject-don't-guess idiom.
- **`Envelope.frame(codecId, blob)` re-serialization** on the read path (`ChatDirectoryService.kt:149`) — DROPPED. This mirrors the §10 precedent verbatim (`DirectoryService.kt:132`); the 8-byte rebuild is off any hot path and is the established pattern. Changing it would diverge from §10 for no measurable gain.

## Code review: NOT YET RUN
