# codec-dedup-and-send-hardening — plan

Source: audit-2026-06-06 quality sweep (`(transient, deleted after ship) .ai-dev/audits/audit-2026-06-06.md`, `## Quality sweep` section), PM triage 2026-06-06 — findings C8 (send hardening) and A1–A4 + B5 (duplication + cleanly-removable dead code) chosen "fix now". Findings B6/B7/C9 and D10 deliberately excluded (see Out of scope).

This is an **internal-quality remediation**: a behaviour-preserving refactor plus one defensive failure-path hardening. No user-visible feature change. The overriding constraint, stated once and binding on every task below: **the coder must not edit any existing test.** Every change is either internal (private symbols, behaviour-preserving) or strictly additive, so the full existing suite compiles and passes unchanged; new tests are added for the new behaviour.

## Scenarios

1. **Send/publish degrades gracefully on a native crypto failure (C8).** When the native AEAD seal fails at runtime (a spurious libsodium `crypto_aead_..._encrypt` failure, surfaced today as an uncaught `IllegalStateException`), a directory/chat-directory **publish** operation returns its existing typed `Failed` outcome instead of propagating an uncaught exception out of the call. Observable: the publish caller receives a typed failure it can retry, the process does not crash.
2. **Parsing untrusted directory/descriptor bytes behaves exactly as before (A1).** After the triplicated bounds-checked byte cursor is replaced by one shared reader, every directory-entry and chat-descriptor parse produces byte-for-byte the same `Verified` / `Dropped` / `NotReady` / reject outcome as today — including on truncated, oversized, and malformed input (the reject-don't-guess + bounded-allocation guarantees are unchanged).
3. **Directory and chat-directory services behave exactly as before (A2).** After the `directory/` ⟷ `chatdirectory/` near-clones are collapsed onto shared generic internals, the public `DirectoryService` / `ChatDirectoryService` / their factories keep identical observable behaviour: same fetch→verify→supersede→publish pipeline, same §10-vs-§11 collection segments, same grouping key (signing-pubkey hex vs chat-id hex), same chat-directory dm-drop rule.
4. **Name-minting and hashing behave exactly as before (A3).** After the Base32-lower alphabet is centralised into `HashTag`, every content-addressed name and well-formed-name gate accepts and rejects exactly the same strings as today.
5. **AEAD framing behaves exactly as before (A4).** After the nonce/tag/key size constants are single-sourced, seal/open produce and accept byte-identical blobs; the libsodium-constant validation still holds.
6. **Dead zeroize methods are gone (B5).** `ChatKey.destroy()` and `Identity.destroy()` (zero callers anywhere — main, test, androidTest) are removed; nothing references them, so nothing changes at runtime.

## Existing behaviors this feature touches

(from `docs/user-journeys.md` + the substrate reviews — what must not break)

- **Reject-don't-guess parsing of untrusted bytes** (crypto/message/directory reviews; threat-model SC16 path-traversal gate + bounded-allocation zip-bomb guard). The shared cursor and any codec change must preserve every bound, every reject, and the well-formed-name alphabet gate.
- **Directory / chat-directory publish + read pipeline** (`directory`, `chat-directory` features): fetch-and-verify, supersede resolution, the rejected counter, secret-copy/try-seal/wipe-in-finally hygiene, idempotent `MKCOL`/`PUT`. Must be identical after the collapse.
- **AEAD seal/open round-trip and content-addressing** (`crypto` feature): random per-seal nonce, header-as-AAD, `OpenResult.Rejected` on any tamper/truncation. Must be byte-identical.
- **Message build/open path** (`message-model`): `serialize → signAndSerialize → sealEnvelope` and its inverse. The shared-cursor and constant changes must not alter it.
- **The crypto seal contract as observed by existing tests**: existing tests call `aead.seal` / `MessageCrypto.sealEnvelope` and assert success/throw behaviour. These signatures and their thrown-exception types **stay as they are** — the C8 fix is added at the publish callers, not by changing the crypto primitive's contract (which would force test edits).

## Contracts

(internal API changes; all public types/signatures that existing tests touch stay stable)

- **Shared bounded byte-cursor** (A1) — one internal reader exposing the bounds-checked `take` / `u8` / `u16` / `u64` operations with the existing limit ceiling, replacing the three private copies (`ChatDescriptorCodec.Cursor`, `DirectoryEntryCodec.Cursor`, `message` `ByteCursor`). Same overrun semantics (throw/return as the codecs already rely on). The two codecs and the message reader consume it internally; their public parse APIs are unchanged.
- **`HashTag` Base32-lower alphabet** (A3) — a single `HashTag`-owned constant for the RFC-4648 base32-lowercase set, referenced by the four current private declarations (`MessageId`, `ChangeEntry`, `DirectoryPaths`, `ChatDirectoryPaths`). Same character set.
- **Single-sourced AEAD sizes** (A4) — `Aead.NONCE_BYTES` / `TAG_BYTES` and `ChatKey.KEY_BYTES` derive from one source aligned with the libsodium `AEAD.XCHACHA20POLY1305_IETF_*` constants `LazySodiumCrypto` already validates against. Public constant names stay (tests referencing them still compile); only their derivation is unified.
- **Shared directory-orchestration internals** (A2) — `DirectoryService`/`ChatDirectoryService`, their `*Paths`, `*Crypto`, and `*Factory` collapse onto shared generic internals parameterised by collection segment + grouping-key extractor (+ the chat-directory dm-drop). The **public service/factory types and method signatures the existing tests use stay identical** — the shared core is an internal implementation detail behind them.
- **Publish failure path broadened (C8)** — `DirectoryService.publishEntry` and `ChatDirectoryService.publishChatEntry` already return a typed `Failed` on `IllegalArgumentException`; broaden that handling so a **native AEAD seal failure** (`IllegalStateException` from `aeadEncrypt`) also maps to the same typed `Failed` rather than propagating. Additive — the return type and the existing IAE→Failed behaviour are unchanged. Catch the crypto-failure narrowly (a dedicated crypto failure signal, not a blanket `catch (Throwable)`) so unrelated bugs still surface — the coder picks the narrowest mechanism that needs no existing-test edit (e.g. catching the specific exception the seal path throws at the publish boundary).

## Stack expectations touched

(from `docs/stack-notes.md`)

- **libsodium (lazysodium-android), XChaCha20-Poly1305 AEAD**: nonce = 24 bytes, tag = 16 bytes, key = 32 bytes — the `AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES` / `_ABYTES` / `_KEYBYTES` libsodium constants. The A4 single-sourcing must keep the AEAD-layer sizes equal to these libsodium constants (the value-home stays the libsodium-derived numbers, not a re-invented literal). Source: stack-notes crypto section.
- **Base32 (RFC 4648) lowercase** name-minting alphabet (`a–z2–7`): the A3 centralised constant must be exactly this set — the output charset of `HashTag.tag`. Source: stack-notes / protocol layout §2–§3.

## Interaction scenarios

The codecs and `HashTag` are pure functions over input bytes (no shared mutable state) — provably isolated for A1/A3/A4. The directory services (A2) and the publish path (C8) touch shared WebDAV transport + per-poll read loops:

- **When a native seal failure occurs mid-publish while a concurrent poll/read is in flight (C8):** the publish returns typed `Failed`; the concurrent read loop is unaffected (no shared crypto state is corrupted, the wipe-in-finally still runs). Verified by a publish-fails test asserting `Failed` and that no exception escapes.
- **When the collapsed `DirectoryService`/`ChatDirectoryService` run their read loops over the same transport (A2):** behaviour (supersede resolution, rejected counter, collection isolation §10 vs §11) is identical to the pre-collapse services — the shared core must not cross-wire the two collections or share the grouping key. Verified by the existing directory + chat-directory suites passing unchanged.

## Test plan

- **Existing tests that must pass: ALL existing tests, unchanged.** This is the primary acceptance gate — the refactor is behaviour-preserving and the C8 fix is additive, so the entire current `app/src/test` + `app/src/androidTest` suite must stay green with **zero edits to any existing test file**. If any existing test would need editing to pass, the change is mis-scoped — stop and surface it.
- **New tests:**
  - `shared cursor parity`: the shared bounded cursor rejects/accepts truncated, exact-boundary, oversized, and well-formed inputs identically to the behaviour the three former copies enforced (overrun → same outcome; `u64` boundary; limit ceiling). given a crafted byte buffer / when read past the limit / then the same throw-or-reject as before.
  - `centralised base32 alphabet`: the `HashTag` alphabet constant equals the RFC-4648 base32-lower set and the four former call sites accept/reject the same characters (a name with `1`/`8`/`9`/`0` or uppercase is rejected; `a–z2–7` accepted). Comment cites the RFC-4648 source.
  - `aead size constants single-sourced`: `Aead.NONCE_BYTES==24`, `TAG_BYTES==16`, `ChatKey.KEY_BYTES==32`, and they equal the libsodium `AEAD.XCHACHA20POLY1305_IETF_*` constants — one assertion that the derivation agrees with libsodium (stack-spec test; comment cites the libsodium constant names). given the AEAD layer / when compared to the libsodium constants / then equal.
  - `publish degrades on native seal failure` (C8): with a fake `NativeCrypto` whose `aeadEncrypt` throws the native-failure exception, `DirectoryService.publishEntry` and `ChatDirectoryService.publishChatEntry` return typed `Failed` and no exception escapes. given a seal-failing native / when publish is called / then `Failed`, not a crash.
- **Interaction scenario tests:**
  - `publish-fails leaves read loop usable`: after an induced seal failure on publish, a subsequent read/verify on the same service still succeeds (no corrupted crypto state, wipe-in-finally ran). Sets up seal-failure then a normal read / verifies normal outcome.
- **Stack-spec tests:** the `aead size constants single-sourced` test above doubles as the libsodium stack-spec test (asserts against the libsodium constants, not a self-chosen literal); the `centralised base32 alphabet` test asserts against the RFC-4648 set.
- **Removal coverage (B5):** `ChatKey.destroy()` / `Identity.destroy()` removal needs no new test — they have zero callers, so the suite compiling + passing unchanged is the proof nothing depended on them.

## Docs to update

- `docs/architecture.md`: add a short decision/note recording the shared bounded-cursor and the `directory`/`chatdirectory` shared-core consolidation (so the dedup is a recorded structural decision, not silent), and the single-source homes for the base32 alphabet (`HashTag`) and AEAD sizes (libsodium constants). Updated by `Builder` post-coding.
- `docs/threat-model.md`: **review, expected no posture change.** The shared cursor consolidates SC16-relevant parse code without weakening any bound, and C8 converts a potential crash into a typed failure (strictly safer). `Builder` confirms no Threat row changes (or records the C8 robustness improvement if it wants a row). Named here because the change touches a security-relevant parse + crypto-failure surface.

## Out of scope

- **C8 message-build path hardening** — `MessageEnvelope.build` → `sealEnvelope` is **not yet wired to any user-reachable send** (there is no UI / send-orchestration; `SendWriter` takes pre-sealed bytes and has no production caller that seals). Hardening the message build/send path's failure surface belongs to the **send-orchestration / UI feature** that first makes it user-reachable, where the right typed send-failure contract can be designed end-to-end (and tests written for it). This plan hardens only the two directory publish paths, which already own a typed `Failed` contract today.
- **B6 `ChatKey.export()` removal** — called by ~30 existing tests and intended for the deferred invite/QR feature. Removing it requires editing existing tests (forbidden here) and removes a feature-intended accessor. **Accept-with-context** (recorded in `.ai-dev/backlog.md`): keep until the invite feature lands, which will either consume or retire it with its own test migration.
- **B7 `ChatPaths` gen-1 helpers** (`INBOX`, `inbox()`, 3-arg `message()`) — called only by existing sync/transport tests. Removing them requires editing those tests (forbidden here). **Accept-with-context**: revisit when the legacy transport tests are next touched.
- **C9 `Aead.open()` zeroization gap** — the only un-wiped buffers are the recovered plaintext (which **is** the return value and must survive) and the nonce copy (**non-secret** — nonces are public). Wiping adds churn for no security gain. **Descoped** with rationale.
- **D10 `agreeChatKey` chat-id binding** — already backlogged as a feature blocker (`.ai-dev/backlog.md`, quality-sweep D10); the consuming private-chat/directory/rotation feature owns the fix. Not touched here.
- **Deferred `importRawKey` / `openSealed` raw-overload wipe consistency** — backlogged with D10; settled by the invite feature. Not touched here.
