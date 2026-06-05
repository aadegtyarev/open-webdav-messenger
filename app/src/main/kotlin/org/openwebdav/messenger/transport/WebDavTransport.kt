package org.openwebdav.messenger.transport

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.protocol.MessageId

/**
 * WebDAV transport capability (`docs/features/webdav-transport_plan.md` → Contracts) implementing
 * the access rules in `docs/protocol/webdav-layout.md` §6.
 *
 * Exposes list-inbox / read-file / write-file / delete-file / create-collection, each returning a
 * [WebDavResult] that distinguishes success, conflict (`412`), rate-limit (`429`, after back-off),
 * and transport error. Payloads are opaque `ByteArray`s — the transport assigns no meaning.
 *
 * A single shared [OkHttpClient] is injected (one instance reused across all calls — stack-notes);
 * all blocking I/O runs through [CallExecutor] on `Dispatchers.IO`.
 */
internal class WebDavTransport(
    private val config: ConnectionConfig,
    client: OkHttpClient,
    backOff: BackOffPolicy = BackOffPolicy(),
    delayer: Delayer,
) {
    private val requests = WebDavRequests(config)
    private val executor = CallExecutor(client, backOff, delayer)

    /**
     * List a collection with `PROPFIND Depth: 1` (§6). Returns the direct member entries with
     * their ETags; the collection's own self-entry is filtered out by name.
     */
    suspend fun list(path: String): WebDavResult<List<InboxEntry>> {
        gate(path)?.let { return it }
        val selfName = path.trimEnd('/').substringAfterLast('/')
        return executor.execute(requests.propfind(path)) { response ->
            mapMultistatus(response, requests.url(path), selfName)
        }
    }

    /**
     * GET a message file and verify the reader integrity check (§3): the recomputed
     * `content-hash` of the file bytes must match the suffix of [name]. On mismatch/truncation
     * the file is "not ready" → [ReadResult.NotReady] (skip + retry, never surfaced as corruption).
     * On success the opaque post-header blob (§5) is returned.
     */
    suspend fun read(
        path: String,
        name: String,
    ): WebDavResult<ReadResult> {
        gate(path)?.let { return it }
        return executor.execute(requests.get(path)) { response ->
            mapRead(response, name)
        }
    }

    /**
     * GET a **content-addressed** file whose whole name is `b32lower(SHA-256(file-bytes))[0:N]` (the
     * §10.4 directory-entry naming), as opposed to a §2 message-id (`order-token "~" content-hash`).
     * Verifies the §3 / §10.6 on-read content-hash: the recomputed `b32lower(SHA-256(file-bytes))[0:N]`
     * (truncated to the length of [expectedHash]) must equal [expectedHash]. On mismatch / truncation /
     * a 404 race / oversize, the file is "not ready" → [ReadResult.NotReady] (skip + retry, never a
     * crash). On success the post-header blob (§5) is returned with the on-disk codec-id. The §1–§9
     * message read path ([read]) is unchanged — this is an additive sibling for the community-scoped
     * directory whose names are not §2 message-ids.
     */
    suspend fun readContentAddressed(
        path: String,
        expectedHash: String,
    ): WebDavResult<ReadResult> {
        gate(path)?.let { return it }
        return executor.execute(requests.get(path)) { response ->
            mapContentAddressedRead(response, expectedHash)
        }
    }

    /**
     * PUT [bytes] (an already-framed envelope, §5) to [path]. Message writes are unconditional
     * and append-only (§6); pass [ifMatch] only for mutable `meta/` files. A `412` becomes a typed
     * [WebDavResult.Conflict].
     */
    suspend fun write(
        path: String,
        bytes: ByteArray,
        ifMatch: String? = null,
    ): WebDavResult<Unit> {
        gate(path)?.let { return it }
        return executor.execute(requests.put(path, bytes, ifMatch)) { response ->
            when {
                response.isSuccessful -> WebDavResult.Success(Unit)
                response.code == HTTP_PRECONDITION_FAILED -> WebDavResult.Conflict
                else -> httpError(response)
            }
        }
    }

    /** DELETE a processed message file (§6). A 404 is treated as already-deleted success. */
    suspend fun delete(path: String): WebDavResult<Unit> {
        gate(path)?.let { return it }
        return executor.execute(requests.delete(path)) { response ->
            if (response.isSuccessful || response.code == HTTP_NOT_FOUND) {
                WebDavResult.Success(Unit)
            } else {
                httpError(response)
            }
        }
    }

    /**
     * MKCOL to ensure a collection exists (§6). An already-exists response (`405`/`301`) is
     * handled idempotently as success.
     */
    suspend fun ensureCollection(path: String): WebDavResult<Unit> {
        gate(path)?.let { return it }
        return executor.execute(requests.mkcol(path)) { response ->
            if (response.isSuccessful || response.code in IDEMPOTENT_MKCOL_CODES) {
                WebDavResult.Success(Unit)
            } else {
                httpError(response)
            }
        }
    }

    /**
     * Fail-closed pre-flight for every verb: reject a cleartext baseUrl (credential protection,
     * Security constraints) and a path that contains dot-segments or non-§0 characters
     * (path-traversal guard). Returns the typed failure to short-circuit, or `null` to proceed.
     * The chat-root is validated alongside the relative path since both become URL segments.
     */
    private fun gate(path: String): WebDavResult<Nothing>? {
        PathSafety.requireHttps(config.normalizedBaseUrl)?.let { return it }
        PathSafety.validatePath(config.normalizedChatRoot)?.let { return it }
        PathSafety.validatePath(path)?.let { return it }
        // Validate that the base URL + chat-root combination is parseable by OkHttp before any verb.
        // PathSafety passes a URL that OkHttp's stricter parser may still reject (e.g. invalid host
        // characters). Catch IAE here so callers always get a typed TransportError, never a crash.
        try {
            "${config.normalizedBaseUrl}/${config.normalizedChatRoot}/".toHttpUrl()
        } catch (e: IllegalArgumentException) {
            return WebDavResult.TransportError(code = null, message = "unparseable connection URL: ${e.message}")
        }
        return null
    }

    private fun mapMultistatus(
        response: Response,
        basePathUrl: String,
        selfName: String,
    ): WebDavResult<List<InboxEntry>> {
        if (response.code != HTTP_MULTI_STATUS) return httpError(response)
        // Parse from the raw bytes, not response.body.string(): string() decodes per the
        // Content-Type charset and re-encoding to UTF-8 for the DOM parser would double-decode a
        // non-UTF-8 multistatus. The XML's own declaration drives decoding (§9 review finding).
        val body = response.body?.bytes() ?: return malformed("empty PROPFIND body")
        val parsed = PropfindParser.parse(body, basePathUrl)
        val members = parsed.filter { it.name != selfName }
        return WebDavResult.Success(members)
    }

    private fun mapRead(
        response: Response,
        name: String,
    ): WebDavResult<ReadResult> =
        mapVerifiedRead(response) { bytes ->
            // §3: the recomputed content-hash must equal the `~`-split suffix of the §2 message-id.
            // A missing / unsplittable id is treated as a failed check (→ not-ready), never a Ready.
            val expected = MessageId.splitMessageId(name)?.second
            expected != null && MessageId.contentHash(bytes) == expected
        }

    private fun mapContentAddressedRead(
        response: Response,
        expectedHash: String,
    ): WebDavResult<ReadResult> =
        mapVerifiedRead(response) { bytes ->
            // §3 / §10.6: recompute b32lower(SHA-256(file-bytes)) and truncate to the name's length. A
            // mismatch means tampered OR still-uploading (Yandex computes the hash after upload) — both
            // are not-ready/skip, never surfaced as a valid entry (the directory drops it, reads the rest).
            MessageId.contentHash(bytes).take(expectedHash.length) == expectedHash
        }

    /**
     * Shared GET-body mapping for the two content-hash-verified read paths ([read] over §2 message-ids,
     * [readContentAddressed] over §10.4 content-addressed names). The flow is identical — 404 race →
     * not-ready, bounded [readCapped] (oversize/empty → not-ready), then the §3 content-hash tamper check,
     * then the §5/§7 frame parse → [ReadResult.Ready] — and differs only in [hashOk], the per-path
     * predicate that decides whether the recomputed hash matches what the name claims. Unifying both
     * security-relevant read paths here means the DoS bound and the on-read hash check evolve in lock-step.
     */
    private inline fun mapVerifiedRead(
        response: Response,
        hashOk: (ByteArray) -> Boolean,
    ): WebDavResult<ReadResult> {
        // A 404 GET is a benign race (a concurrent processor deleted the file, or eventual-
        // consistency lag), the same family as delete()'s 404→success. Map to the skip result,
        // not a hard transport error (§3 not-ready / review finding 6).
        if (response.code == HTTP_NOT_FOUND) return WebDavResult.Success(ReadResult.NotReady)
        if (!response.isSuccessful) return httpError(response)
        val bytes =
            when (val read = readCapped(response)) {
                is CappedRead.Oversize -> return WebDavResult.Success(ReadResult.NotReady)
                is CappedRead.Empty -> return WebDavResult.Success(ReadResult.NotReady)
                is CappedRead.Bytes -> read.value
            }
        if (!hashOk(bytes)) return WebDavResult.Success(ReadResult.NotReady)
        // Expose the validated on-disk header alongside the blob so the reader rebuilds the EXACT §5.1
        // AEAD AAD (codec-id and all) rather than assuming codec 0x00 (review finding 4). An unparseable
        // frame (bad magic / unknown envelope-version / unknown codec-id) is §7 reject → not-ready.
        val frame = Envelope.readFrame(bytes) ?: return WebDavResult.Success(ReadResult.NotReady)
        return WebDavResult.Success(ReadResult.Ready(frame.blob, frame.codecId))
    }

    /**
     * Read the GET body bounded by [MAX_MESSAGE_FILE_BYTES] to prevent an OOM DoS: any chat member
     * can write a multi-GB file under the one shared credential (Topology A), so the reader must
     * never materialize an unbounded body. We read at most cap+1 bytes — a server can lie about
     * `d:getcontentlength`, so the hard cap is on the actual bytes read — and reject if it exceeds
     * the cap (review finding 1). Oversize is treated as not-ready (skip), never surfaced as a
     * valid message.
     */
    private fun readCapped(response: Response): CappedRead {
        val source = response.body?.source() ?: return CappedRead.Empty
        // request() pulls from the network into the buffer up to the requested byte count; if the
        // body has more than the cap, the buffer holds cap+1 here and we reject without buffering
        // the (potentially multi-GB) remainder.
        val limit = MAX_MESSAGE_FILE_BYTES + 1
        source.request(limit)
        if (source.buffer.size > MAX_MESSAGE_FILE_BYTES) return CappedRead.Oversize
        val bytes = source.readByteArray()
        return if (bytes.isEmpty()) CappedRead.Empty else CappedRead.Bytes(bytes)
    }

    private fun <T> httpError(response: Response): WebDavResult<T> =
        WebDavResult.TransportError(code = response.code, message = "HTTP ${response.code}")

    private fun <T> malformed(message: String): WebDavResult<T> = WebDavResult.TransportError(code = null, message = message)

    private sealed interface CappedRead {
        data class Bytes(val value: ByteArray) : CappedRead

        data object Oversize : CappedRead

        data object Empty : CappedRead
    }

    private companion object {
        const val HTTP_MULTI_STATUS = 207
        const val HTTP_PRECONDITION_FAILED = 412
        const val HTTP_NOT_FOUND = 404
        val IDEMPOTENT_MKCOL_CODES = setOf(301, 405)

        /**
         * Hard cap on a single message-file body. The MVP payload is text + a small Markdown
         * subset (no media, no attachments), so 1 MiB is generous headroom for the largest
         * realistic encrypted text message while bounding the OOM-DoS surface (review finding 1).
         */
        const val MAX_MESSAGE_FILE_BYTES = 1L * 1024 * 1024
    }
}

/** Result of [WebDavTransport.read]: a verified message blob, or "not ready" (§3). */
internal sealed interface ReadResult {
    /**
     * A complete, §3-hash-verified file: the opaque post-header [blob] (§5) plus the §5-byte-5 [codecId]
     * read from the actual on-disk header. The reader rebuilds the exact §5.1 AEAD AAD from [codecId]
     * (not an assumed 0x00) and reject-with-reasons an unsupported codec (review finding 4). [codecId]
     * defaults to [Envelope.CODEC_NONE] so callers constructing a Ready in the codec-none MVP path
     * (and the predating transport tests that assert only [blob]) are unaffected.
     */
    data class Ready(val blob: ByteArray, val codecId: Byte = Envelope.CODEC_NONE) : ReadResult {
        override fun equals(other: Any?): Boolean = other is Ready && codecId == other.codecId && blob.contentEquals(other.blob)

        override fun hashCode(): Int = 31 * blob.contentHashCode() + codecId
    }

    data object NotReady : ReadResult
}
