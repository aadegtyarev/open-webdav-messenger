# message-model — plan

> The **message format** that lives INSIDE the AEAD ciphertext-blob — the structured plaintext the crypto substrate seals/opens. Pure format + serialization + sign/verify + crypto integration. It does NOT touch the on-disk layout, polling, sync, or local history (those are the next `sync` feature), no UI, no compression. Backend/infrastructure.

## Scenarios

System behaviors (no UI/sync yet — the message format the sync layer will move):

1. A **text message** can be built with: a message-id, the chat-id, the sender's identity, optional **reply-to** (a target message-id it quotes), a **body** = plain text + the small Markdown subset (raw markdown text — rendering is the UI feature), and a send timestamp. It serializes to bytes, gets signed by the sender, and is sealed by the existing AEAD into an envelope.
2. A **reaction** can be built as its own message: a message-id, chat-id, sender, a **target message-id** (the message being reacted to), and a **reaction index** into the fixed 5-reaction set (the concrete glyphs are a UI concern). Same serialize → sign → seal path.
3. Opening an envelope (existing crypto) yields the plaintext bytes; this feature **deserializes** them back into a typed message (text or reaction), and **verifies the sender's Ed25519 signature** over the message. A valid message exposes its fields; a failed verification or a malformed/unknown-version/unknown-kind message is a **typed rejection** (dropped, never a crash, never shown as a valid message).
4. **Sender authentication within a shared-key chat:** because the AEAD key is shared by all chat members, AEAD alone cannot distinguish senders. Each message is therefore **signed with the sender's identity Ed25519 key**, and carries the sender's identity public key, so a member cannot forge a message as another member (they cannot sign with someone else's key). (Tying that public key to a human is the directory / safety-number job, a later feature; this feature provides the per-message signature so it is verifiable once the directory exists, and gives tamper-evidence + intra-chat non-repudiation now.)
5. The format is **versioned and extensible** — a version + kind tag, with reject-don't-guess on an unknown version or kind, so future kinds (edit, delete, system) and fields can be added without breaking older readers.

## Existing behaviors this feature touches

`docs/user-journeys.md` is a skeleton. Concrete touch points are code/contract:
- **Crypto substrate** (`crypto/Aead`, `MessageCrypto`): the serialized+signed message bytes are exactly the plaintext fed to `Aead.seal`; opening returns those bytes for this feature to deserialize+verify. The envelope framing (§5, codec-id 0x00) and AEAD must not change.
- **Identity substrate** (`identity/IdentityCrypto`): message signing uses `sign`/`verify` (Ed25519 detached) and the sender's identity keypair; the sender's identity public key is embedded in the message.
- The `webdav-transport` content-addressing and on-disk layout are untouched (this feature does not write to the disk — `sync` does).

## Contracts

New, internal (the byte format is the interop contract — pinned in `docs/protocol/webdav-layout.md` by the architect as part of this feature):

- **Message types** — a `TextMessage` (message-id, chat-id, sender public identity, optional reply-to message-id, markdown body text, timestamp) and a `ReactionMessage` (message-id, chat-id, sender, target message-id, reaction-index 0..4). A sealed-in-the-envelope `Message` sum type {Text, Reaction}, extensible.
- **Serialize / build** — `serialize(message) → plaintextBytes` (a versioned, extensible encoding: version + kind tag + fields); `signAndSerialize(message, senderSignSecret) → signedPlaintextBytes` producing the bytes the AEAD seals (signature + signed payload).
- **Open / verify** — `parse(plaintextBytes) → Parsed | Rejected`: deserialize, then verify the Ed25519 signature against the embedded sender public key; `Rejected` on bad signature / malformed / unknown version|kind. Never throws into the caller.
- **Integration** — composes with the existing `MessageCrypto`/`Aead`: build → sign+serialize → seal (envelope); open → parse+verify. The transport content-hashes the resulting envelope bytes (unchanged).

## Stack expectations touched

From `docs/stack-notes.md` (Last reviewed 2026-06-03):

- **Crypto library (libsodium)**: "libsodium provides XChaCha20-Poly1305 AEAD … Clean AEAD with associated data." The serialized+signed message is the AEAD plaintext. Source: https://doc.libsodium.org/secret-key_cryptography/aead
- **Crypto — Public-key (Ed25519)**: "verify with `crypto_sign_verify_detached` (returns 0 on success, -1 on failure — treat -1 as a hard reject, never best effort)." Message verification must hard-reject on failure. Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
- **Kotlin**: "Platform types from Java interop are not null-checked … must be treated as nullable" — no `!!` on parse paths; values crossing the crypto boundary are nullable. Source: https://kotlinlang.org/docs/null-safety.html#nullability-and-java-interoperation
- **Integration (envelope)**: the message plaintext format is an addition to the on-disk contract `docs/protocol/webdav-layout.md` (the inner-plaintext structure, distinct from §5 outer framing), authored by the architect as part of this feature; validated by JVM serialize→sign→seal→open→verify→parse round-trip tests.

## Key design decisions (mine — recorded for the architect/coder)

- **Per-message Ed25519 signature** (scenario 4): every message is signed by the sender; the sender's identity public key is embedded; `parse` verifies it. Decided (consistent with the PM's anti-impersonation priority that pulled identity forward). The signature covers the serialized message fields; it sits inside the AEAD plaintext (so it is also confidentiality-protected).
- **Reaction = a first-class message kind** carrying a target message-id + a reaction index `0..4`; the concrete 5 glyphs are deferred to the UI feature (Behavioral-contract reaction enum stays a UI-fixed index space).
- **Reply = a field** (optional `reply-to` message-id) on a text message, not a separate kind.
- **Encoding** (exact bytes: binary TLV vs length-prefixed vs CBOR-like) is the architect's call when authoring the format in `webdav-layout.md`; constraints: versioned, extensible (future kinds/fields), reject-don't-guess, compact-ish (compression is a later feature; codec-id stays 0x00).
- **Ordering** uses the outer order-token (§4); the inner message carries a sender send-timestamp for display only (best-effort, not trusted).

## Interaction scenarios

The message-model composes with crypto + identity (shared key material + the AEAD/sign primitives); not isolated.

- **Round-trip through crypto:** a text message built by sender A, sign+serialized, sealed with a chat key, then opened with the same chat key and parsed → the exact fields back, signature verifies against A's embedded key.
- **Intra-chat impersonation attempt:** a message whose claimed sender public key does not match the key that signed it (or a tampered signed payload) → `parse` returns `Rejected` (the signature does not verify), so a member cannot post as another member.
- **A reaction references an as-yet-unknown message-id:** `parse` succeeds (the reaction is well-formed); applying it to a missing target is the sync/UI layer's graceful-degradation concern, not a parse error (mirrors the §4 "reply may precede its target" rule).
- **Unknown version or kind tag** (a future-version message read by this build) → `Rejected` (reject-don't-guess), not a partial parse.
- **Malformed/truncated plaintext** (e.g. a wrong chat key decrypted to garbage, or a corrupt blob) → `parse` returns `Rejected`, never an index crash.

## Test plan

- Existing tests that must pass: all `crypto`, `identity`, `webdav-transport` suites stay green.
- New tests (JVM unit — lazysodium-java + system libsodium for the sign/verify + AEAD):
  - `text_message_roundtrip`: build → sign+serialize → seal → open → parse+verify yields the identical fields (id, chat, sender, reply-to, body).
  - `reaction_message_roundtrip`: a reaction (target id + index) survives the full round-trip with index and target intact.
  - `signature_verifies_against_embedded_sender_key`: a validly-signed message parses to a verified message.
  - `forged_sender_rejected`: a message whose signature does not match the embedded/claimed sender key → `Rejected` (impersonation blocked).
  - `tampered_payload_rejected`: flipping a field byte after signing → signature fails → `Rejected`.
  - `unknown_version_rejected` / `unknown_kind_rejected`: a version/kind tag this build does not implement → `Rejected`, not a partial parse.
  - `malformed_plaintext_rejected`: truncated/garbage plaintext (e.g. wrong-key decrypt) → `Rejected`, no crash.
  - `reply_to_optional`: a text message with no reply-to round-trips with reply-to absent; with reply-to set, it round-trips present.
  - `markdown_body_preserved`: a body containing the supported Markdown characters round-trips byte-identical (the model carries raw markdown text; no rendering).
  - `reaction_index_bounds`: a reaction index outside 0..4 is rejected on parse (fixed 5-set).
- Interaction scenario tests (one per Interaction scenario):
  - `crypto_roundtrip_cross_key`: seal with a chat key, open+parse with the same key → fields back; open with a different key → AEAD rejects before parse (covered by crypto) — assert the message layer surfaces a typed failure.
  - `impersonation_signature_mismatch_rejected`: as above (forged sender) asserted as the interaction outcome.
  - `reaction_to_unknown_target_parses`: a reaction to a not-yet-seen message-id parses successfully (application deferred to sync/UI).
  - `unknown_version_is_typed_rejection`: future-version message → typed `Rejected`.
- Stack-spec tests (verify the cited rule; reference the source URL in a comment):
  - `ed25519_message_verify_rejects_on_failure`: a bad message signature → verify false / `Rejected` (libsodium -1 = hard reject), never accepted.
  - `message_is_aead_plaintext`: the signed+serialized bytes are exactly what `Aead.seal` encrypts and `open` returns (no separate channel) — the model rides inside the existing envelope.

## Docs to update

- **`docs/protocol/webdav-layout.md`** (owner: `Builder`): author a new section specifying the **inner message plaintext format** (versioned; kind tag {text, reaction}; field layout for each kind incl. sender identity public key + Ed25519 signature; reaction index space 0..4; reply-to optional; reject-don't-guess on unknown version/kind). Distinct from §5 (outer envelope framing) — this is the structure inside the ciphertext.
- **`docs/architecture.md`** (owner: `Builder`): record a decision "Message model — signed plaintext inside the envelope" (per-message Ed25519 signature for intra-chat sender authentication; reaction as a message kind with a 0..4 index; reply as a field). Resolve the Behavioral-contract message-envelope `[?]` items this feature fixes (the inner fields list; reaction index space) — or point to the new webdav-layout section.
- **`AGENTS.md`** Pipeline: no new validator (existing gates cover it; `connectedAndroidTest` only if any path is device-bound — message-model is pure JVM-testable logic, so likely no new instrumented need beyond the existing native sign/verify already proven).

## Out of scope

- **sync** — the on-disk layout rework (shared chat log + per-user change index + retention window), the WorkManager poll loop, ordering/dedup application, local Room history cache: the NEXT feature. This feature only defines and (de)serializes the message; it does not write/read it to/from the disk or schedule anything.
- **UI** — composing/displaying messages, rendering the Markdown, the 5 reaction glyphs, reply display: separate UI feature. This feature carries raw markdown text and a reaction index only.
- **Compression** — codec-id stays 0x00; the message bytes are not compressed here (compression feature later).
- **Directory** — mapping a sender's identity public key to a human/display identity and verifying it (safety numbers): separate feature. This feature embeds + signs with the key and verifies the signature; "is this key really Alice" is the directory's job.
- **Sibling of the "message kind" categorical** — kinds beyond {text, reaction}: **edit**, **delete**, **system/announcement** messages are excluded now (each is a separate downstream behavior + UI); the format is versioned/extensible so they can be added later.
- **Sibling of the "reaction" categorical** — custom/free emoji reactions beyond the fixed 5-set are excluded (MVP is a fixed 5-index set; the glyphs themselves are the UI feature's choice).
