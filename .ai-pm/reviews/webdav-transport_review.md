# webdav-transport — plan compliance review

Reviewed working tree on branch `feature/webdav-transport` (HEAD empty for `app/`; reviewed the uncommitted tree). Read end-to-end: the plan, `docs/protocol/webdav-layout.md`, `docs/stack-notes.md`, `CLAUDE.md`, all `app/src/main` + `app/src/test` sources. Pipeline re-run locally.

## Plan completeness

- "Stack expectations touched" section present, every entry sourced. ✓
- "Interaction scenarios" section present (the transport does shared-disk I/O — correctly not declared `Provably isolated`). ✓
- Not a `hotfix-` topic — no Incident facts required. ✓
- Categorical coverage: the "provider" sibling (Nextcloud), the "polling cadence" sibling (foreground-service), and multi-disk are each listed under Out of scope with a one-line reason. ✓

## Plan compliance (Scenarios)

- ✓ Scenario 1 (configure base URL + app-password, establish connection) — `ConnectionConfig` + `WebDavRequests` (Basic auth via app-password), `TransportFactory`. Exercised indirectly by every transport test through `TestSupport.config`.
- ✓ Scenario 2 (list inbox in one PROPFIND Depth:1; GET; DELETE) — `WebDavTransport.list/read/delete`; tests `propfind_lists_inbox_depth1`, `get_then_delete_roundtrip`.
- ✓ Scenario 3 (PUT content-addressed, append-only, never overwrite) — `ChatPaths.message` + `MessageId.messageId`; test `put_message_uses_content_addressed_name` (idempotent repeat PUT → same path).
- ✓ Scenario 4 (If-Match conditional write; 412 is a typed retry path, not a crash) — `WebDavResult.Conflict`; tests `conditional_put_sends_if_match`, `conditional_put_412_is_typed_conflict`, `conflict_412_retry_path`.
- ✓ Scenario 5 (429 / timeout → exponential back-off + retry) — `CallExecutor` + `BackOffPolicy` + injectable `Delayer`; tests `rate_limit_429_backs_off_and_retries`, `timeout_backs_off_and_retries`.
- ✓ Scenario 6 (project builds; `test`/`lint`/`ktlintCheck` green) — pipeline re-run below: BUILD SUCCESSFUL.

## Test plan coverage

All 16 named tests present and behavior-matched (2 renamed but equivalent), 25 tests total, all pass:

Unit (8): `propfind_lists_inbox_depth1` ✓, `put_message_uses_content_addressed_name` ✓, `conditional_put_sends_if_match` ✓, `conditional_put_412_is_typed_conflict` ✓, `rate_limit_429_backs_off_and_retries` ✓, `timeout_backs_off_and_retries` ✓, `get_then_delete_roundtrip` ✓, `mkcol_creates_collection` ✓ (as `mkcol_creates_collection_and_existing_is_idempotent`).

Interaction (4): `concurrent_writers_distinct_names_no_overwrite` ✓, `reader_skips_incomplete_file` ✓ (asserts a hash-mismatched/truncated GET → `ReadResult.NotReady`, not corruption — the concurrent post-condition, not a happy path), `back_off_window_survives_mid_cycle_429` ✓ (429 mid-cycle, later requests still complete), `conflict_412_retry_path` ✓ (412 then re-PROPFIND/re-PUT success).

Stack-spec (4): `depth_header_is_1_not_infinity` ✓, `if_match_uses_propfind_etag` ✓, `single_okhttpclient_reused` ✓, `response_bodies_closed` ✓ (as `response_bodies_closed_on_every_path`). Each references its source URL in a comment and asserts the cited rule, not the coder's own mapping (e.g. `response_bodies_closed_on_every_path` proves closure indirectly via `connectionPool.connectionCount() == 1` across success/412/429 paths; `if_match_uses_propfind_etag` asserts the sent `If-Match` equals the exact `d:getetag` PROPFIND returned).

Plus 8 protocol-primitive tests (`ProtocolPrimitivesTest`) covering §1.2/§2/§4/§5/§7.

## Spec fidelity (`webdav-layout.md`)

- ✓ content-hash = `b32lower(SHA-256(file-bytes))[0:32]` — `MessageId.contentHash` over the full framed envelope; idempotent + collision-free (test).
- ✓ inbox-id = `b32lower(SHA-256(recipient ‖ 0x1F ‖ chat-id))[0:26]` — `MessageId.inboxId`; `0x1F` domain separator present and tested for collision resistance.
- ✓ order-token field widths 11/8/8 (= 29) — `OrderToken.LENGTH` = 11+1+8+1+8 = 29; Base32hex fixed-width, lexicographically sortable (test). Matches the corrected 29/62 spec summary.
- ✓ 8-byte envelope header: magic `OWDM`, envelope-version `0x01`, codec-id `none = 0x00`, flags/reserved `0x00` — `Envelope.write`.
- ✓ reader integrity check (recompute hash → NotReady on mismatch) — `WebDavTransport.mapRead`; truncated/mismatched GET → `ReadResult.NotReady`, never surfaced as corruption.
- ✓ reject-don't-guess on bad magic / unknown envelope-version / truncation — `Envelope.read` returns null; test `envelope_read_rejects_bad_magic_and_truncation`.

## Stack expectations compliance

- ✓ Single shared `OkHttpClient` — `TransportFactory.sharedClient` (one lazy instance); test `single_okhttpclient_reused`. No `new OkHttpClient` per call in production paths.
- ✓ Response bodies always closed — centralized in `CallExecutor.attempt` via `.execute().use { … }`; verified across success/error/412/429 by `response_bodies_closed_on_every_path`.
- ✓ Depth:1 never infinity — `WebDavRequests.propfind` hardcodes `Depth: 1`; tests assert it and assert-not `infinity`.
- ✓ If-Match/ETag, not LOCK — conditional PUT only; no LOCK anywhere.
- ✓ 429 + timeout exponential back-off with injectable clock — `CallExecutor` + `Delayer`; back-off timing asserted (`1000, 2000` ms).
- ✓ No `!!` on WebDAV/XML Java-interop paths — none in `app/src/main` (grep clean); nullable platform values handled with `?.`/`?:`.
- ✓ Dispatchers.IO for I/O — `CallExecutor` runs in `withContext(ioDispatcher = Dispatchers.IO)`.

## Security

- ✓ No passphrase/key/plaintext written to disk — transport carries opaque `ByteArray` only; `ConnectionConfig` (incl. app-password + chat-root) is in-memory config, never PUT. No crypto exists yet — nothing secret is persisted.
- ✓ PROPFIND XML is XXE-hardened — `PropfindParser.newDocument` disables doctype-decl, external general + parameter entities, and entity-reference expansion.

## Code conventions

- ✓ All source files ≤ 300 lines (largest `WebDavTransport.kt` = 142). Functions within limits; no obvious complexity-10 breach (not a code-quality review — flagged only as a DoD/convention gate).
- ✓ No file-level lint suppressions. One statement-level `@Suppress("UNREACHABLE_CODE")` in `CallExecutor.kt:55` — permitted (not file-level).

## Out-of-scope respected

- ✓ No crypto, compression, UI, sync loop / WorkManager, Nextcloud-specific shaping, foreground-service, or multi-disk leaked into the diff. Envelope `codec-id` slot reserved (writes `none`) without wiring a codec. `meta/` paths reserved, not populated.

## Product Contract

No Product Contract touched — webdav-transport is backend/infrastructure only (no user-visible UI), and `docs/product-map.md` classifies it under "Infrastructure (no user-facing contract)". No `.ai-pm/contracts/` entry required; none expected.

## Definition of Done

- [x] All plan scenarios implemented and tested
- [x] Interaction scenarios have concurrent-state tests
- [x] Stack expectations respected; stack-spec tests pass
- [x] Product Contract honored; Acceptance checks pass; no silent behavior change (N/A — backend-only, no contract)
- [x] Pipeline green (`./gradlew test ktlintCheck lint` → BUILD SUCCESSFUL; 25 tests pass)
- [x] State file updated (`.ai-pm/state/current.md`)
- [x] Product Impact Report present (N/A — no contract touched)
- [x] Docs updates landed: `docs/protocol/webdav-layout.md` created; `docs/architecture.md` reconciled (invariants pinned, file-layout map marked implemented/planned, license → AGPL-3.0, deps table reconciled); `CLAUDE.md` decision #6 (ktlint+Lint, no detekt) + toolchain note; `docs/product-map.md` updated. `LICENSE` (AGPL-3.0) present.
- [x] Expected artifacts exist: plan (`docs/features/webdav-transport_plan.md`), this review, no contract (correctly — not user-facing).

**DoD: pass**

## Blocking

None.

## Notes (product)

1. `OrderToken.kt:11` KDoc still reads "order-token = … ; 30 chars" while `OrderToken.LENGTH` correctly computes 29 (11+1+8+1+8) and the spec summary was corrected to 29/62. This is a stale doc comment only — the code and tests use the correct 29. Why it matters: harmless now, but a leftover "30" in a load-bearing protocol file invites a future reader to re-introduce the off-by-one the architect just fixed in the spec. Worth a one-line comment fix when the branch is next touched; not worth blocking the PR.
2. `Envelope.read` rejects bad magic and unknown envelope-version but intentionally does **not** validate `codec-id` or assert `flags`/`reserved == 0x00`, deferring codec interpretation to the crypto/compression feature (documented in the code). `webdav-layout.md` §7 says "same [reject-don't-guess] rule for an unknown codec-id". Why it matters: for this feature the post-header blob is opaque and the transport assigns it no meaning (§5), so deferring codec validation to the feature that actually inflates is a defensible boundary — but the PM/architect should confirm the codec-reject responsibility is explicitly owned by the compression feature's plan so the §7 guarantee is not silently dropped at the seam. Non-blocking; a scope-ownership confirmation, not a defect in this diff.

## Verdict

approve

<!-- orchestrator appends after code-review pass: -->
## Code review findings

## Code review
