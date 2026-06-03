package org.openwebdav.messenger.transport

/**
 * Typed transport result distinguishing the outcomes the plan and
 * `docs/protocol/webdav-layout.md` §6 require callers to handle separately:
 *
 *  - [Success] — the verb completed; carries a verb-specific [value].
 *  - [Conflict] — a conditional `PUT` was rejected with `412 Precondition Failed`
 *    (a competing writer changed the target). A normal retry path, not a crash.
 *  - [RateLimited] — `429 Too Many Requests` persisted past the back-off budget.
 *  - [CleartextRejected] — the baseUrl is not `https://`; the app refuses to send the Basic-auth
 *    credential over cleartext (Security constraints — credentials never cross an unencrypted hop).
 *  - [InvalidPath] — a chat-root or path segment contains `..`/dot-segments or characters outside
 *    the §0 filename-safe alphabet; rejected fail-closed rather than normalized (path-traversal guard).
 *  - [TransportError] — any other failure (non-2xx status, I/O error, malformed response).
 */
internal sealed interface WebDavResult<out T> {
    data class Success<out T>(val value: T) : WebDavResult<T>

    data object Conflict : WebDavResult<Nothing>

    data object RateLimited : WebDavResult<Nothing>

    data class CleartextRejected(val message: String) : WebDavResult<Nothing>

    data class InvalidPath(val message: String) : WebDavResult<Nothing>

    data class TransportError(
        val code: Int?,
        val message: String,
        val cause: Throwable? = null,
    ) : WebDavResult<Nothing>
}

/** A single entry returned by a `PROPFIND Depth: 1` listing (§6). */
internal data class InboxEntry(
    /** Last path segment — the message-id / file name (§2). */
    val name: String,
    /** ETag from `d:getetag`, used for conditional writes (§6). `null` if the server omits it. */
    val etag: String?,
    /** Content length from `d:getcontentlength`, or `null` if absent. */
    val contentLength: Long?,
    /** True for a `<d:collection/>` resourcetype (a folder, not a message file). */
    val isCollection: Boolean,
)
