package org.openwebdav.messenger.transport

/**
 * Fail-closed validation of WebDAV targets before any request is built
 * (`docs/protocol/webdav-layout.md` §0 character set; Security constraints).
 *
 * Two guards live here:
 *  - [requireHttps] — the baseUrl scheme MUST be `https://`. The app sends the app-password as a
 *    Basic-auth header; over `http://` that credential travels in cleartext. Reject rather than
 *    silently leak (Security constraints — credentials never cross an unencrypted hop).
 *  - [validatePath] — a chat-root-relative path MUST consist only of §0 filename-safe segments and
 *    MUST NOT contain `..`/`.` dot-segments. `toHttpUrl()` silently normalizes `../`, which would
 *    let a crafted path escape the credential-scoped chat-root; we reject before that happens.
 */
internal object PathSafety {
    /** §0: allowed characters in a single path segment (`~` is allowed inside message-ids). */
    private val SAFE_SEGMENT = Regex("[A-Za-z0-9._~-]+")

    /** Loopback hosts: cleartext here never leaves the device, so the credential is not exposed. */
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")

    /**
     * @return `null` if [baseUrl] is acceptable, otherwise a [WebDavResult.CleartextRejected].
     *   `https://` is always accepted. `http://` is rejected for any real host so the Basic-auth
     *   app-password never crosses an unencrypted network hop (Security constraints); the sole
     *   exception is a loopback host (the MockWebServer test harness), where cleartext stays on the
     *   device and the credential is never exposed.
     */
    fun requireHttps(baseUrl: String): WebDavResult.CleartextRejected? {
        val scheme = baseUrl.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme == "https") return null
        if (scheme == "http" && isLoopback(baseUrl)) return null
        return WebDavResult.CleartextRejected(
            "baseUrl scheme must be https to protect the app-password; got '${scheme.ifEmpty { "(none)" }}'",
        )
    }

    private fun isLoopback(baseUrl: String): Boolean {
        val authority = baseUrl.substringAfter("://", missingDelimiterValue = "").substringBefore('/')
        // Strip the optional :port. A bracketed IPv6 literal ([::1]) keeps its brackets; a bare
        // host:port splits on the last colon.
        val host =
            when {
                authority.startsWith("[") -> authority.substringBefore(']') + "]"
                ':' in authority -> authority.substringBeforeLast(':')
                else -> authority
            }
        return host.lowercase() in LOOPBACK_HOSTS
    }

    /**
     * @return `null` if [path] is a safe chat-root-relative path, otherwise a
     *   [WebDavResult.InvalidPath]. Empty path is allowed (the chat-root itself); each non-empty
     *   segment must match the §0 alphabet and must not be a `.`/`..` dot-segment.
     */
    fun validatePath(path: String): WebDavResult.InvalidPath? {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return null
        for (segment in trimmed.split('/')) {
            val reason = rejectReason(segment)
            if (reason != null) return WebDavResult.InvalidPath("invalid path segment '$segment': $reason")
        }
        return null
    }

    /** @return `null` if [segment] is a safe single segment, otherwise the rejection reason. */
    private fun rejectReason(segment: String): String? =
        when {
            segment.isEmpty() -> "empty segment"
            segment == "." || segment == ".." -> "dot-segment not allowed (path-traversal guard)"
            !SAFE_SEGMENT.matches(segment) -> "character outside the §0 filename-safe alphabet"
            else -> null
        }
}
