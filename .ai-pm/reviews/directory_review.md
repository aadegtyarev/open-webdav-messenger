# directory — plan-compliance review (Pass 1)

Reviewed: 2026-06-04 · Branch: `feature/directory` · Change type: backend/infrastructure substrate (no UI)
Baseline: `docs/features/directory_plan.md` + `.ai-pm/arch/directory_arch.md`

## Plan compliance

### Scenarios

- ✓ **1. Publish self** — `DirectoryService.publishEntry` builds {display-name + two public keys}, Ed25519-signs (`DirectoryEntryCodec.signAndSerialize`), AEAD-seals under the community key (`DirectoryCrypto.sealEntry` → `MessageCrypto`), writes one content-addressed append-only file. Tests: `publish_then_read_roundtrips_verified_entry`, `entry_is_ciphertext_only_on_disk` (DirectoryRoundTripTest).
- ✓ **2. Read + verify** — `readDirectory` lists (Depth 1), opens + verifies each entry, returns verified `DirectoryEntry`s; every failure is a typed drop, remaining valid entries still read. Tests: round-trip + the full DirectoryRejectionTest suite (wrong key, tampered, wrong sig, malformed/truncated/wrong-magic, over-cap name).
- ✓ **3. Discovery feeds downstream (not wired)** — `DirectoryEntry` surfaces both verified public keys (`copySigningPublicKey`/`copyBoxPublicKey`/`toPublicIdentity`); no chat start / key agreement / fingerprint here. Test: `publish_then_read_roundtrips_verified_entry` asserts both 32-byte keys round-trip; arch Option 4A confirms no downstream wiring.
- ✓ **4. Update own entry** — `SupersedeResolver` resolves the max signed `version-counter` per signing-pubkey (content-hash tiebreak). Tests: `updated_entry_supersedes_older` (RoundTrip), `deleted_entry_absent_until_republish` (Interaction, re-publish at v2).
- ✓ **5. Unreadable without community key** — wrong/absent key → AEAD-open typed reject (no `DirectoryEntry`). Community key is config-supplied, distinct from disk app-password, never on disk/log. Tests: `read_without_community_key_yields_nothing_readable`, `entry_is_ciphertext_only_on_disk`, `community_key_is_not_the_disk_credential`.
- ✓ **6. Robustness / flat-trust degradation** — one bad/forged/missing entry is dropped, the read is never wedged (iterate-drop-continue, no cursor). Tests: `tampered_entry_does_not_wedge_directory`, `deleted_entry_absent_until_republish`, `wrong_key_entry_dropped_not_crash`.

### Interaction scenarios (one test each — concurrent/post-condition state set up)

- ✓ Two members publish concurrently → both land, distinct names — `concurrent_publishes_both_land`.
- ✓ Publish while another reads → picked up next read — `publish_during_read_picked_up_next_read`.
- ✓ Entry tampered/deleted (flat trust) → dropped/absent, rest read, owner re-publishes — `tampered_entry_does_not_wedge_directory` + `deleted_entry_absent_until_republish`.
- ✓ Updated entry supersedes older → exactly one (newest) per member — `updated_entry_supersedes_older`.
- ✓ Directory coexists with per-chat folders → only `directory/` touched, no `log/`/`changes/`/`meta/` — `directory_read_does_not_touch_chat_folders` (asserts against `ChatPaths`).
- ✓ Wrong community key (stale-after-rotation) → typed drop, not crash — `wrong_key_entry_dropped_not_crash`.

### Stack expectations (each stack-spec test verifies the cited rule + carries its source URL)

- ✓ Ed25519 detached, hard-reject on `-1` — `entry_signature_hard_rejects_on_failure` (libsodium public-key-signatures URL); verifies against the carried, signed-range pubkey.
- ✓ AEAD 24-byte fresh random nonce per seal — `entry_aead_uses_24_byte_random_nonce` (libsodium AEAD URL + §5.1/§10.2); asserts `NPUBBYTES == 24` and two seals differ in the nonce window.
- ✓ Community key independent of disk credential, never on disk (SC3/SC4) — `community_key_is_not_the_disk_credential` (cites SC3).
- ✓ Path-traversal rejection (SC16) — `directory_path_rejects_traversal` (cites SC16 + §0/§10.4); foreign/`../`/out-of-alphabet names dropped before any GET.
- ✓ `PROPFIND Depth: 1`, never infinity (RFC 4918) — `propfind_depth_is_one` (cites RFC 4918 + §6/§10.6); fake disk records every Depth header.
- ✓ MKCOL idempotent + TLS — `ensureCollection` idempotent (405/301), transport `gate()` rejects cleartext baseUrl; exercised on every publish round-trip.
- ✓ Append-only + content-addressing + on-read hash check — content-addressed `b32lower(SHA-256(file-bytes))[0:32]` name, `WebDavTransport.readContentAddressed` recomputes-and-rejects-on-mismatch; tampered-byte path in `tampered_entry_rejected_other_entries_still_read`.

### Contracts

- ✓ **Publish / Read / DirectoryEntry** honored — typed `PublishOutcome` / `DirectoryReadResult`, never throws; `DirectoryEntry{displayName, signingPublicKey(32), boxPublicKey(32)}` matches `publicIdentity()` shape.
- ✓ **Local cache** correctly **deferred** per arch Option 4A — `readDirectory` recomputes from disk each call, no Room entity/DAO/Flow added. This is a deliberate, flagged deferral (arch note §171, state file Remaining, architecture decision 12 "No local cache this feature"), not a silent drop. Confirmed not a gap.

### Out of scope — respected

No scope creep observed: no chat-directory, no QR/fingerprint UI, no onboarding key distribution, no DH chat establishment, no host attestation, no rotation/roster, no local cache. The `directory/` package and the additive `WebDavTransport.readContentAddressed` are the only production changes; §1–§9 of webdav-layout are byte-for-byte unchanged (verified — only §10 appended, plus the status footer).

### Security (security-bearing project; Cryptography/key-mgmt, Data-at-rest, Network/transport, PII surfaces touched)

- ✓ Threat-model update present and real — T20 (impersonation-by-name, accepted-limitation → deferred QR), T21 (community-key compromise, community-barrier tier), T22 (metadata exposure, A5 class as T17/T18); SC18 (signed + hard-reject entries) + SC19 (community key independent of disk credential, never on disk); Realized-by row + non-goals extended; `Last reviewed: 2026-06-04`. A security-touching plan that omitted this would be blocking — it is present and honest.
- ✓ SC3/SC4 — community key independent of disk credential, never on disk/log (config-supplied; `community_key_is_not_the_disk_credential`).
- ✓ SC5 — only public keys published; signing secret copied in-memory for the sign then `fill(0)`-wiped; `published_entry_carries_only_public_keys` asserts the 64-byte secret never enters the entry.
- ✓ SC16 — content-addressed `[a-z2-7]{32}` names, foreign names rejected pre-GET; transport `gate()` is the second fail-closed line.
- ✓ SC13 — TLS-only via transport `gate()` (cleartext baseUrl rejected); reused unchanged.
- ✓ SC11 / append-only + content-hash-on-read — one bad entry never wedges the read; tamper detected by Poly1305 tag and on-read hash.
- ✓ Hard-reject on Ed25519 `-1` — verified last, over the exact signed range, against the embedded (signed) pubkey.

## Definition of Done (backend/infrastructure row: items 1, 2, 4, 5, 7)

- [x] All plan scenarios implemented and tested (1–6 + all 8 base + 6 interaction + 5 stack-spec + 2 instrumented)
- [x] Interaction scenarios have concurrent/post-condition-state tests (6/6)
- [x] Stack expectations respected; stack-spec tests pass (5/5 cite source URLs; all rules upheld in code)
- [x] Product Contract — correctly **skipped** with the honest skip line in the test commit (`Skips Product Contract: backend substrate, no user-visible behavior change`); no UI surface; no silent behavior change
- [x] Pipeline green — `./gradlew test` (21 directory JVM tests, 0 failures/0 errors; full suite green), `./gradlew ktlintCheck` green, `./gradlew lint` green (re-run this review: BUILD SUCCESSFUL)
- [x] State file updated (`.ai-pm/state/current.md` — Status, Done, Remaining, Validation, Touched files, the Option-4A deferral flagged for this checker)
- [n/a] Product Impact Report — no user-facing contract touched
- [x] Docs updates landed — webdav-layout §10 authored (§1–§9 untouched); architecture decision 12 + SC18/SC19 + module-map flip + Realized-by row; threat-model T20/T21/T22 + non-goals; all in this branch
- [x] Expected artifacts exist — plan, this review, arch note; no contract required (not user-facing)
- [n/a] Product-readiness gate — non-user-facing (every scenario subject is the member/system over the directory engine; no UI). Advocate artifact correctly not required (state file records the gate does not fire)
- [n/a] Validation gate — software-kind project (`## Project kind` absent in CLAUDE.md ⇒ software); no `## Validation` section emitted

**DoD: pass**

## Blocking

None.

## Notes (product)

1. **connectedAndroidTest passed on a real device this run, not in CI.** The two native instrumented tests (real lazysodium-android AEAD seal/open + Ed25519 sign/verify on the device ABI) passed on a connected device (M2102J20SG / Android 12), so the native crypto path the directory adds is proven on hardware. But the CI emulator gate (architecture decision 8) is still open across the project — the native ABI load is not yet gated automatically on every PR. Not blocking for this feature (it ran green on a device), but the project-wide gap means a future ABI/native regression could slip through CI until decision 8 is closed. Why it matters: the only validator that catches an `UnsatisfiedLinkError` is the device run; without CI it depends on a developer having a device attached.

2. **Counter-reset display edge is an accepted, documented limitation.** If a member restores the *same* Ed25519 signing key but a *reset* version-counter (rare — a reinstall normally yields a new keypair), the content-hash tiebreak may resolve to the older display-name. This is a non-security display glitch, correctly recorded in webdav-layout §10.5 and the arch note, and never lets an entry signed by a *different* key win for a member. Surfaced only so the PM is aware it is a deliberate trade-off, not an oversight. No action needed.

## Verdict

approve

<!-- The trail below is the ONE review section the orchestrator owns, not pm-plan-checker.
     See WORKFLOW.md "Edit-ownership rule" — the Pass-2 code-review trail is the single
     carve-out to "orchestrator does not edit content artefacts". -->
## Code review findings

Pass 2 (`code-review`, high effort — 3 correctness + security + cleanup angles). **No correctness or security bugs found.** The one security candidate (lossy UTF-8 on display-name) was refuted: the Ed25519 signature is verified over the raw `nameBytes`, so integrity is intact, and §10.3 treats the display-name as raw bytes (rendering/normalization is the UI feature's concern). One actionable cleanup finding:

1. **(cleanup / reuse) `WebDavTransport.mapRead` and `mapContentAddressedRead` are ~95% duplicated** — `WebDavTransport.kt` (mapRead ~L151–175, mapContentAddressedRead ~L182–202). Both do: 404→NotReady, `readCapped` (Oversize/Empty→NotReady), a content-hash check, then `Envelope.readFrame`→Ready. They differ **only** in how the expected hash is derived (`MessageId.splitMessageId(name).second` exact-compare vs `MessageId.contentHash(bytes).take(expectedHash.length)` prefix-compare). Risk: a future fix to the shared logic (the bounded `readCapped`, the 404/empty/oversize handling, or the frame-parse) in one method silently misses the other — and these are **security-relevant read paths** (the DoS bound + the §3 content-hash tamper check). Fix: extract one private helper, e.g. `mapVerifiedRead(response, hashOk: (ByteArray) -> Boolean)`, and have both call sites pass their hash predicate. Behavior must stay byte-identical (all existing transport + directory tests stay green); the §1–§9 message read path must not change semantics.

Considered and NOT actioned (recorded for the trail): (a) the `Envelope.readFrame`→`Envelope.frame`→`openEnvelope` deframe-then-reframe in `DirectoryService.fetchAndVerify` — it mirrors the established sync-reader pattern for rebuilding the exact §5.1 AEAD AAD and has no observable cost; changing it would ripple into the message path's transport/crypto API (out of scope). (b) `SupersedeResolver` recomputing the hex grouping key per `offer()` — negligible (hex of 32 bytes, once per listed entry); caching on `DirectoryEntry` would compute it even when unused. Neither is debt this feature should take on.

## Code review: 2026-06-04 — passed

Pass 2 cleared. Finding 1 (the `mapRead` / `mapContentAddressedRead` duplication) was fixed in `82d7ed8` — both paths now delegate to a single `inline fun mapVerifiedRead(response, hashOk)` differing only in the per-path hash predicate; behavior verified byte-identical (the original conditions are preserved exactly, all existing transport + directory tests stayed green unchanged), three JVM gates green. No correctness or security findings. The two considered-and-not-actioned items above stand (consistent with existing patterns / negligible).
