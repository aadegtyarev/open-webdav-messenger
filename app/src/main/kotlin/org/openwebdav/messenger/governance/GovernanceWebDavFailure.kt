package org.openwebdav.messenger.governance

import org.openwebdav.messenger.transport.WebDavResult

internal fun <T> governanceFailure(result: WebDavResult<*>): WebDavResult<T> =
    when (result) {
        WebDavResult.Conflict -> WebDavResult.Conflict
        WebDavResult.RateLimited -> WebDavResult.RateLimited
        is WebDavResult.CleartextRejected -> result
        is WebDavResult.InvalidPath -> result
        is WebDavResult.TransportError -> result
        is WebDavResult.Success<*> -> error("successful WebDAV result cannot be propagated as failure")
    }
