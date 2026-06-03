# Execution state

Single source of truth for the currently active task. Overwritten as the task progresses; archived to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` on completion.

PM reads this when curious about progress; PM never edits it. Agents read it as their first step and update it as their last step.

---

## Task

message-model — the message format inside the AEAD ciphertext: text + reaction kinds, fields (id/chat/sender/reply-to/body+markdown/timestamp; reaction target+index 0..4), versioned serialization, per-message Ed25519 sender signature, crypto integration. Backend-only. (Split: sync is the next feature.)

## Status

coding

## Done

- Plan approved (split confirmed: message-model now, sync next), saved to docs/features/message-model_plan.md
- Product map updated (message-model in Infrastructure bucket); branch feature/message-model
- pm-architect: authored §8 inner message plaintext format in webdav-layout.md + decision #11 + resolved behavioral-contract envelope [?] items
- pm-coder: implemented message-model (new app/.../message/ package) — TextMessage/ReactionMessage + Message sum type, versioned TLV serialize (§8.2), signAndSerialize (Ed25519 §8.3), parse+verify (reject-don't-guess §8.1), MessageEnvelope seam composing MessageCrypto/Aead. Full JVM test suite added.
- pm-architect: corrected §8 (2026-06-04) — dropped the unsatisfiable inner self message-id (a fixed point over SHA-256 of the sealed bytes); identity is now solely the §2 file name; reply-to/target-id carry other messages' full §2 file names. Updated §8.1/§8.2 prose, §8.4/§8.5 field tables, §8.6, decision #11.
- pm-coder: brought message-model in line with corrected §8 — removed the inner message-id field from both kinds (types, serializer, parser, TLV tag set); renumbered TLV tags to the new contiguous closed sets (text: chat-id 0x01 / reply-to 0x02 / body 0x03 / send-timestamp 0x04; reaction: chat-id 0x01 / target-id 0x02 / reaction-index 0x03); repointed reply-to/target-id to carry full §2 file names validated by a new MessageId.isWellFormedMessageId (§2 grammar: ~ split + 29-char order-token [0-9a-z-] + 32-char content-hash [a-z2-7]); removed the moot innerIdMatchesEnvelope cross-check from MessageEnvelope (kept contentName). Tests updated: dropped self-id assertions, added malformed_reference_rejected + wellformed_reference_to_unknown_message_parses; all prior behavioral/stack-spec tests stay green. Pipeline green: test + lint + ktlintCheck. NOT committed (per instruction).
- pm-coder (Pass-2 review fixes): (1) SECURITY — TlvFields.read no longer pre-sizes the field map from the untrusted §8.2 field-count; it caps the count against `available_bytes / MIN_TLV_TRIPLE_BYTES` BEFORE allocating (a count that cannot fit → typed BAD_FIELDS reject, no ~0.5 MB alloc, happens before the §8.3 signature check) and sizes the map from a small constant (4). (2) MessageSerializer assembles the TLV fields ONCE into a `List<Tlv>` and derives the written field-count from `list.size` — removed the separate `fieldCount(message)` branch (single source of truth, no serialize drift). (3) New shared `BigEndian` codec — `writeUint16Be`/`writeUint64Be`/`readUint64Be` consolidate the matched BE uint64 encode/decode pair (was hand-rolled in MessageSerializer.writeTimestampTlv ↔ MessageParser.uint64); SEND_TIMESTAMP_BYTES now references BigEndian.UINT64_BYTES. (4) Dropped the dead pubkey-length null branch in MessageParser.publicIdentityOf — senderPub comes from take(32) so the size re-check was unreachable; replaced with a once-checked companion `init` require(SENDER_PUBKEY_BYTES == PublicIdentity.SIGN_PUB_BYTES) build-time guard and a direct PublicIdentity(...) construction. §8 wire format byte-identical; reject-don't-guess preserved. Tests added: absurd_field_count_rejected_without_large_allocation, lying_field_count_too_low_rejected, field_count_equals_emitted_tlv_count. Pipeline green: test (101) + lint + ktlintCheck. NOT committed (per instruction).

## Remaining

- code-review re-check of the Pass-2 fixes → commit → merge

## Touched files

- app/src/main/kotlin/org/openwebdav/messenger/message/Message.kt (types)
- app/src/main/kotlin/org/openwebdav/messenger/message/MessageFormat.kt (constants)
- app/src/main/kotlin/org/openwebdav/messenger/message/MessageSerializer.kt (serialize + §8.3 sign; single-source field-count via List<Tlv>)
- app/src/main/kotlin/org/openwebdav/messenger/message/ByteCursor.kt (bounds-checked reader)
- app/src/main/kotlin/org/openwebdav/messenger/message/BigEndian.kt (shared BE uint16/uint64 codec — Pass-2 fix 3)
- app/src/main/kotlin/org/openwebdav/messenger/message/TlvFields.kt (TLV decode; untrusted field-count cap — Pass-2 fix 1)
- app/src/main/kotlin/org/openwebdav/messenger/message/MessageFormat.kt (constants; SEND_TIMESTAMP_BYTES → BigEndian.UINT64_BYTES)
- app/src/main/kotlin/org/openwebdav/messenger/message/ParseResult.kt (typed result + RejectReason)
- app/src/main/kotlin/org/openwebdav/messenger/message/MessageParser.kt (parse + verify)
- app/src/main/kotlin/org/openwebdav/messenger/message/MessageEnvelope.kt (crypto integration seam)
- app/src/main/kotlin/org/openwebdav/messenger/protocol/MessageId.kt (added isWellFormedMessageId — §2 reference grammar validator)
- app/src/test/kotlin/org/openwebdav/messenger/message/MessageTestSupport.kt
- app/src/test/kotlin/org/openwebdav/messenger/message/MessageRoundTripTest.kt
- app/src/test/kotlin/org/openwebdav/messenger/message/MessageRejectionTest.kt
- app/src/test/kotlin/org/openwebdav/messenger/message/MessageStackSpecTest.kt
- .ai-pm/state/current.md (this file)

## Next step

review — re-check the four Pass-2 review fixes (untrusted field-count cap, single-source field-count, shared BE uint64 codec, dead pubkey null branch), then commit → merge. The §8 wire format is byte-identical to the reviewed parsing core (no on-wire change), so the §8.6 id model and all reject semantics are untouched. The sync feature uses MessageEnvelope.contentName (the §2 content-hash over the sealed bytes) as the PUT path / dedup / ordering key.

## Validation

JVM: ./gradlew test (lazysodium-java — serialize/sign/seal/open/verify round-trips). lint/ktlintCheck. Likely NO new connectedAndroidTest (no new native/Keystore surface — sign/verify already device-proven in identity).

## Notes

Key decision: per-message Ed25519 signature (sender's identity key) for intra-chat anti-impersonation (AEAD shared key can't distinguish senders); sender pubkey embedded; parse verifies. Reaction = a message kind (target id + index 0..4, glyphs deferred to UI); reply = a field. Encoding = architect's call in webdav-layout (versioned/extensible/reject-don't-guess). NEXT feature sync reworks the on-disk layout (shared chat log + per-user index + retention window) + decision #2. Done substrates: webdav-transport, crypto, identity.

---

## How to use this file

- **Agent step 1** — read this file before doing anything else. If it says "done", do not start work without explicit PM instruction to start a new task.
- **Agent step last** — overwrite this file with the new state before stopping. Each Done item maps to one logical step that just finished; Remaining stays accurate; Next step points at the next concrete action.
- **Session restart** — re-read this file. It should be enough to continue without scrolling chat history.
- **Task complete** — copy this file to `.ai-pm/state/archive/<topic>-<YYYY-MM-DD>.md` and reset this one to a new task or to "Status: idle".
