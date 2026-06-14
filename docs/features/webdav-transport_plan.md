# webdav-transport — plan

> First feature. Besides the transport, it bootstraps the entire Android project (Gradle, manifest, module structure) — so it is larger than later features will be. Backend/infrastructure only: no UI, no crypto, no message model. The transport moves opaque byte blobs to/from a WebDAV disk at the paths the protocol spec defines.

## Scenarios

System behaviors (no user-visible UI yet — this is the transport substrate crypto and sync build on):

1. The app is configured with a WebDAV base URL + app-password (Topology A — one shared credential per chat) and establishes a connection to the disk.
2. The app lists its own inbox in one round-trip (`PROPFIND Depth: 1`) and reads new message files (`GET`), then deletes processed ones (`DELETE`).
3. The app writes a message into a recipient's inbox (`PUT`) using a content-addressed file name (= message-id); one file per message, append-only — an existing file is never overwritten.
4. A conditional write (`If-Match` on the resource ETag) detects a competing writer: a `412 Precondition Failed` is a normal retry path, not a crash.
5. On Yandex.Disk `429 Too Many Requests` or a network timeout, the client backs off exponentially and retries, rather than failing the whole poll/write cycle.
6. The project builds and the full pipeline (`test`, `lint`, `ktlintCheck`) is green from this feature onward.

## Existing behaviors this feature touches

None — this is the first feature. `docs/user-journeys.md` is still a skeleton (no journeys defined yet), so there is no existing behavior to regress. This feature establishes the build pipeline that all later features must keep green.

## Contracts

New, internal to the app (detailed byte-level layout lives in `docs/protocol/webdav-layout.md`, authored as part of this feature):

- **On-disk protocol layout** (`docs/protocol/webdav-layout.md`) — the interoperability contract between app instances: chat-root folder structure, per-user inbox folders, content-addressed message-file naming (name derived from message-id), append-only one-file-per-message rule, the message-id grammar, an ordering token, and the envelope framing fields at the non-cryptographic level (a slot where the ciphertext blob lives; crypto plugs in later). This file MUST exist before transport code is written (per `docs/stack-notes.md` → Integration contracts).
- **Transport capability** (internal) — a WebDAV transport component exposing: list-inbox (PROPFIND Depth:1 → entries with ETag), read-file (GET), write-file (PUT, content-addressed name, conditional `If-Match`), delete-file (DELETE), create-collection (MKCOL) — each returning a typed result that distinguishes success, conflict (`412`), rate-limit (`429` → backed-off retry), and transport error. Opaque `ByteArray` payloads in/out; the transport assigns no meaning to the bytes.
- **Connection config** — base URL + credentials (app-password) + chat-root path; held in memory / app-private storage, never written to the disk.

## Stack expectations touched

From `docs/stack-notes.md` (all `Last reviewed: 2026-06-03`):

- **OkHttp + WebDAV**: "Create a single OkHttpClient and reuse it … each client holds its own connection pool and thread pools." Source: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/index.html
- **OkHttp + WebDAV**: "Always close the response body … an unclosed response body leaks a connection." Source: https://square.github.io/okhttp/
- **OkHttp + WebDAV**: "Poll the chat folder with Depth: 1, never rely on infinity." Servers MUST support Depth 0 and 1; infinity MAY be disabled. Source: https://datatracker.ietf.org/doc/html/rfc4918#section-9.1
- **OkHttp + WebDAV**: "Use the `If` / `If-Match` header with the resource's ETag (returned as `d:getetag` in PROPFIND) on PUT to detect a competing writer." Optimistic concurrency, not WebDAV LOCK. Source: https://datatracker.ietf.org/doc/html/rfc4918#section-8.6
- **OkHttp + WebDAV**: "Yandex.Disk rate-limits PROPFIND and returns `429 Too Many Requests` under frequent polling … back off on 429 and minimise round-trips." Source: https://github.com/kopia/kopia/issues/88
- **OkHttp + WebDAV**: Yandex endpoint `https://webdav.yandex.com`, TLS, authenticate with an **app password for Files/WebDAV** (not the account password). Source: https://yandex.com/dev/disk/webdav/
- **Kotlin**: "values crossing the Java boundary (many crypto/HTTP libraries are Java) must be treated as nullable" — no `!!` on WebDAV paths; "Blocking I/O (network, disk) must not run on the main/UI dispatcher; use coroutines on `Dispatchers.IO`." Source: https://kotlinlang.org/docs/null-safety.html#nullability-and-java-interoperation
- **Gradle**: "Android Lint aborts the build on errors by default … keep it that way." `./gradlew lint`. Source: https://developer.android.com/studio/write/lint
- **Platform filesystem layout**: "Only **ciphertext** and protocol metadata may be written here. Never write a passphrase, a derived key, or plaintext private-chat content to the disk." (This feature writes only opaque blobs + protocol files — boundary respected; no plaintext handling exists yet.) Source: https://developer.android.com/training/data-storage
- **Integration contract** (WebDAV disk): artifact `docs/protocol/webdav-layout.md` delivered by runtime PROPFIND/MKCOL/PUT/GET/DELETE; validated by `./gradlew test` (MockWebServer asserting exact verbs/headers: Depth, If-Match/ETag) + a real-account smoke test proving PUT/PROPFIND/GET round-trips and 429 back-off.

## Interaction scenarios

The transport does network I/O against shared disk state written by multiple peers, so it is not isolated. Scenarios:

- **Two senders write to the same recipient inbox concurrently:** because each message file is content-addressed (distinct names), both files land; neither overwrites the other. (Same message-id from a duplicate send → identical content-addressed name → idempotent PUT, not a conflict.)
- **A reader polls (PROPFIND/GET) while a writer is mid-PUT of a new file:** the reader either does not yet see the in-progress file (it appears on the next poll) or gets a clean complete GET; a partial/failed GET of an incompletely-written file is treated as "not ready", skipped, and retried next cycle — never surfaced as corruption.
- **A `429` arrives in the middle of a poll cycle:** the cycle backs off and resumes; it does not abort the whole sync or crash. Subsequent requests honor the back-off window.
- **A conditional `PUT` returns `412` (a competing writer changed the target):** the transport reports a typed conflict; the caller's retry path re-reads and re-attempts rather than treating it as a fatal error.

## Test plan

- Existing tests that must pass: none yet (first feature) — but this feature establishes `./gradlew test`, `./gradlew lint`, `./gradlew ktlintCheck` as the green pipeline all later features inherit.
- New tests (MockWebServer, off-device):
  - `propfind_lists_inbox_depth1`: given a configured transport, when listing an inbox, then the request is `PROPFIND` with header `Depth: 1` (never `infinity`) and parsed entries expose their ETag.
  - `put_message_uses_content_addressed_name`: when writing a blob, then the target path is derived from the message-id (content-addressed), and a repeat PUT of the same id targets the same path (idempotent).
  - `conditional_put_sends_if_match`: when writing with optimistic concurrency, then the `PUT` carries an `If-Match` header with the ETag obtained from PROPFIND.
  - `conditional_put_412_is_typed_conflict`: given the server returns `412`, then the transport returns a typed conflict result (not an exception/crash).
  - `rate_limit_429_backs_off_and_retries`: given the server returns `429` then `200`, then the client waits (exponential back-off) and the call ultimately succeeds; back-off timing is asserted via an injected clock/scheduler.
  - `timeout_backs_off_and_retries`: given a request times out then succeeds, then the client retries with back-off rather than failing hard.
  - `get_then_delete_roundtrip`: when reading a file then deleting it, then the verbs/paths are `GET` then `DELETE` on the same resource.
  - `mkcol_creates_collection`: when ensuring a chat/inbox folder exists, then `MKCOL` is issued (and an existing-collection response is handled idempotently).
- Interaction scenario tests (one per Interaction scenario):
  - `concurrent_writers_distinct_names_no_overwrite`: two writes of different message-ids to one inbox produce two distinct resources; neither PUT overwrites the other.
  - `reader_skips_incomplete_file`: a GET that returns an incomplete/failed body is treated as "not ready" and skipped, not surfaced as corruption; the entry is retried on the next list.
  - `back_off_window_survives_mid_cycle_429`: a 429 mid-cycle backs off and the cycle resumes to completion without aborting other requests.
  - `conflict_412_retry_path`: a 412 on conditional PUT yields a typed conflict the caller can re-attempt.
- Stack-spec tests (verify against the cited rule, not the coder's own mapping; each references its source URL in a comment):
  - `depth_header_is_1_not_infinity` — RFC 4918 §9.1: poll uses Depth: 1.
  - `if_match_uses_propfind_etag` — RFC 4918 §8.6: the If-Match value equals the ETag returned by PROPFIND.
  - `single_okhttpclient_reused` — OkHttp guidance: one shared client instance across calls.
  - `response_bodies_closed` — OkHttp guidance: no leaked response body on any path (success, error, 412, 429).
- Manual / optional verification (needs real credentials, cannot run in CI without an account):
  - Live smoke against a real Yandex.Disk account + a generic WebDAV server (e.g. rclone/self-host): configure with an app-password, PUT a blob into an inbox, PROPFIND-list it, GET it back, DELETE it, and confirm 429 back-off under rapid polling. PM provides a test account/app-password.

## Docs to update

- **Create `docs/protocol/webdav-layout.md`** (owner: `Builder`) — the on-disk protocol layout spec. Authored as the FIRST step of this feature, before transport code, since it is the contract the code implements.
- **`docs/architecture.md`** (owner: `Builder`): pin the `[?]` Behavioral-contract invariants this feature resolves (message-id grammar, inbox/file-naming, ordering token) — either inline or by reference to `webdav-layout.md`; and fix the now-stale "project license is TBD" note in the Dependencies policy → AGPL-3.0.
- **`AGENTS.md`** File-layout note: reconcile the "planned" module map against the real tree once code lands (the map currently says it is planned, not observed).
- **`AGENTS.md` Pipeline / Open decisions**: record that decision #6 is resolved — ktlint + Android Lint chosen, detekt not used (already the pipeline default; this feature makes it real).

## Out of scope

- **Crypto** (AEAD, Argon2id, Keystore) — transport carries opaque blobs; encryption is a separate feature.
- **Compression** — separate feature; the envelope codec-id slot is defined in `webdav-layout.md` but no codec is wired here.
- **UI** (Compose chat surface, settings, polling-interval control) — separate feature.
- **Message model & sync loop** (fan-out logic, dedup, ordering application, WorkManager polling) — transport provides the primitives; the sync feature composes them.
- **Sibling of the "provider" categorical — Nextcloud-specific handling** (the `X-Requested-With` header requirement, `/public.php/dav` public-share endpoints, NC version quirks): excluded now. This feature targets **generic WebDAV (RFC 4918) + Yandex.Disk**. Nextcloud likely works via the standard path but its quirks are validated in a separate plan — different provider-specific request shaping.
- **Sibling of the "polling cadence" categorical — foreground-service mode** (sub-15-min delivery, decision #5): excluded now; belongs to the sync feature. This feature does no scheduling — it exposes transport calls a future worker drives.
- **Multi-disk** — one disk/credential per chat (Topology A); multiple disks at once is a future enhancement.
