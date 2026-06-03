# message-model — plan compliance review

Scope: pure-JVM inner message-plaintext format (§8 of `docs/protocol/webdav-layout.md`) — serialize + sign + parse + verify + crypto integration. Backend/infrastructure. **No Product Contract touched** (no UI surface; confirmed `.ai-pm/contracts/` does not exist and both the plan and architecture decision #11 mark this backend-only). connectedAndroidTest correctly N/A — sign/verify/AEAD run via lazysodium-java + system libsodium on the JVM, no Keystore/native app-only surface.

## Plan completeness
- ✓ Stack expectations touched section present, each entry carries a source URL (libsodium AEAD, libsodium Ed25519, Kotlin null-safety).
- ✓ Interaction scenarios section present (composes with crypto + identity — not isolated; correctly declared).
- ✓ §8 authored in `docs/protocol/webdav-layout.md`; architecture decision #11 recorded; both carry the §8 correction (no inner self-id) consistently.

## Plan compliance — Scenarios
- ✓ Scenario 1 (text build → serialize → sign → seal) — `MessageSerializer.signAndSerialize` + `MessageEnvelope.seal`; test `text_message_roundtrip`, `markdown_body_preserved`.
- ✓ Scenario 2 (reaction as own kind, target + index 0..4) — `ReactionMessage` + serializer; test `reaction_message_roundtrip`, `reaction_index_bounds`.
- ✓ Scenario 3 (open → deserialize → verify; typed rejection on bad/malformed/unknown) — `MessageParser.parse` → `ParseResult.{Parsed,Rejected}`; tests across `MessageRejectionTest`.
- ✓ Scenario 4 (intra-chat sender auth via embedded Ed25519 pubkey + signature over it) — `sender-id-pubkey` is inside the signed range `[0..len-64)`; test `forged_sender_rejected`, `impersonation_signature_mismatch_rejected`.
- ✓ Scenario 5 (versioned + extensible, reject-don't-guess) — `FORMAT_VERSION` + `kind` byte; tests `unknown_version_rejected`, `unknown_kind_rejected` (0x03 reserved).

## Plan compliance — Test plan (matched by behavior; names align)
- ✓ text_message_roundtrip — MessageRoundTripTest
- ✓ reaction_message_roundtrip — MessageRoundTripTest
- ✓ signature_verifies_against_embedded_sender_key — MessageRoundTripTest
- ✓ forged_sender_rejected — MessageRejectionTest
- ✓ tampered_payload_rejected — MessageRejectionTest (flips a body byte in the signed payload)
- ✓ unknown_version_rejected / unknown_kind_rejected — MessageRejectionTest
- ✓ malformed_plaintext_rejected — MessageRejectionTest (empty, garbage 50/200, truncated valid msg)
- ✓ reply_to_optional — MessageRoundTripTest (absent + present)
- ✓ markdown_body_preserved — MessageRoundTripTest (byte-identical UTF-8 incl. multibyte)
- ✓ reaction_index_bounds — MessageRejectionTest (0..4 accepted; re-signed 5 → OUT_OF_RANGE)
- ✓ crypto_roundtrip_cross_key — MessageRoundTripTest (same key fields back; wrong key → typed Rejected)
- ✓ impersonation_signature_mismatch_rejected — MessageRejectionTest
- ✓ reaction_to_unknown_target_parses — MessageRejectionTest
- ✓ unknown_version_is_typed_rejection — MessageRejectionTest
- ✓ ed25519_message_verify_rejects_on_failure — MessageStackSpecTest (asserts verify false on -1 path + parser rejects), source URL cited
- ✓ message_is_aead_plaintext — MessageStackSpecTest (sealed bytes == signed+serialized; opened parses), source URL cited
- ✓ malformed_reference_rejected — MessageRejectionTest (bad reply-to; bad target-id alphabet)
- ✓ wellformed_reference_to_unknown_message_parses — MessageRejectionTest

19 message-model tests run; all pass. Crypto (Aead, MessageCrypto, KeySources, CryptoStackSpec), identity, and transport suites re-run green (no failures across any suite).

## §8 fidelity (corrected R1)
- ✓ Fixed prefix `version(1) ‖ kind(1) ‖ sender-id-pubkey(32) ‖ field-count(uint16 BE)` then TLV — `MessageFormat.PREFIX_BYTES = 36`, serializer/parser agree.
- ✓ Signed payload is exactly `[0 .. len-64)`; signature = final 64 bytes (`signatureStart = size - 64`); signer and verifier use the same range.
- ✓ Min length 100 enforced before any read (`MIN_PLAINTEXT_BYTES`, reject not bounds error).
- ✓ NO inner self-id field — neither kind carries one; identity is the §2 file name via `MessageEnvelope.contentName`.
- ✓ reply-to / target-id carry FULL §2 file names validated by `MessageId.isWellFormedMessageId` (alphabet + single-`~` split + 29-char order-token `[0-9a-z-]` + 32-char hash `[a-z2-7]`) — NOT reconstructed from the message's own bytes.
- ✓ reaction-index ∈ 0..4 enforced on parse (`reactionIndex`) and in the `ReactionMessage` ctor.
- ✓ Reject-don't-guess total: unknown version/kind/tag, duplicate tag, missing required tag, length overrun, trailing-byte mismatch (`TlvFields.read` must end exactly at `signatureStart`), bad signature → typed `Rejected`, never throw/partial. `ByteCursor`/`TlvFields` return null on overrun.
- ✓ Signature verified LAST, after structure validation, over the exact embedded `sender-id-pubkey`.

## Stack-spec tests verify the cited rule
- ✓ `ed25519_message_verify_rejects_on_failure` proves libsodium verify returns false on a corrupted signature (the -1 hard reject) AND the parser surfaces `BAD_SIGNATURE` — source URL present.
- ✓ `message_is_aead_plaintext` proves the signed+serialized bytes are byte-identical to what `Aead.seal`/`open` carries (no side channel; codec-id 0x00) — source URL present.

## Reuse / conventions
- ✓ Composes existing `MessageCrypto`/`Aead` + `IdentityCrypto.sign/verify` — no crypto reimplemented (no `crypto_*`/Cipher/MessageDigest in the message package; `MessageId.contentHash` reuses the existing protocol helper).
- ✓ No `!!` on parse paths (only the two doc-comment mentions). No file-level or `@Suppress`.
- ✓ All files ≤ 165 lines (max 300); functions within limits.
- ✓ Scope respected: no Room/WorkManager/Compose/deflate/PROPFIND/disk-write in the message package; no transport imports (PUT/transport mentions are forward-pointer doc comments for the sync seam).

## Out of scope respected
- ✓ No on-disk layout/sync/poll/Room, no UI, no compression (codec 0x00), no directory.
- ✓ Sibling categoricals correctly excluded with reasons in the plan: kinds beyond {text, reaction} (edit/delete/system — reserved 0x03..0x05, reject-don't-guess); custom emoji beyond the fixed 5-set. No sibling silently implemented.

## Definition of Done
- [x] All plan scenarios implemented and tested
- [x] Interaction scenarios have concurrent/condition tests (cross-key, impersonation-mismatch, unknown-target-parses, unknown-version typed rejection)
- [x] Stack expectations respected; stack-spec tests pass (both with source URLs)
- [x] Product Contract honored — N/A (backend-only; no user-facing surface, no contract exists or is required)
- [x] Pipeline green (`test` + `ktlintCheck` + `lint` all BUILD SUCCESSFUL; tests re-run with `--rerun-tasks`, no failures)
- [x] State file updated (`.ai-pm/state/current.md` records the implementation + the §8-correction alignment)
- [x] Product Impact Report — N/A (no contract touched)
- [x] Docs updates landed (webdav-layout §8, architecture decision #11, product-map — all in this working tree)
- [x] Expected artifacts exist (plan, this review; no contract required — not user-facing)

**DoD: pass**

## Blocking
None.

## Notes (product)
None. (The R1 correction — no inner self-id; references are full §2 file names — was an architect-led plan/spec update already reflected consistently in §8, architecture decision #11, the implementation, and the tests; not a deviation.)

## Verdict
approve

<!-- orchestrator appends after code-review pass: -->
## Code review findings

## Code review
