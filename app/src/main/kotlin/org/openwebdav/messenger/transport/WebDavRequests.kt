package org.openwebdav.messenger.transport

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Builds OkHttp [Request]s for the WebDAV verbs in `docs/protocol/webdav-layout.md` §6.
 * Pure request shaping (no I/O), so the verb/header contract is unit-testable via MockWebServer.
 */
internal class WebDavRequests(private val config: ConnectionConfig) {
    private val authHeader = Credentials.basic(config.username, config.appPassword)

    /** Absolute URL for a chat-root-relative [path] (no leading slash). */
    fun url(path: String): String {
        val joined = "${config.normalizedBaseUrl}/${config.normalizedChatRoot}/$path"
        return joined.toHttpUrl().toString()
    }

    /** PROPFIND with `Depth: 1` (§6 — never `infinity`). */
    fun propfind(path: String): Request =
        base(path)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

    /** GET a message file. */
    fun get(path: String): Request = base(path).get().build()

    /** DELETE a message file. */
    fun delete(path: String): Request = base(path).delete().build()

    /** MKCOL to create a collection (§6 — 405/301 handled as success by the caller). */
    fun mkcol(path: String): Request = base(path).method("MKCOL", null).build()

    /**
     * PUT [bytes]. When [ifMatch] is non-null, attach an `If-Match` header carrying the
     * resource ETag for optimistic concurrency (§6). Message PUTs pass `null`
     * (content-addressed, append-only); only mutable `meta/` files use the conditional form.
     */
    fun put(
        path: String,
        bytes: ByteArray,
        ifMatch: String?,
    ): Request {
        val builder = base(path).put(bytes.toRequestBody(OCTET_MEDIA_TYPE))
        if (ifMatch != null) builder.header("If-Match", ifMatch)
        return builder.build()
    }

    private fun base(path: String): Request.Builder =
        Request.Builder()
            .url(url(path))
            .header("Authorization", authHeader)

    private companion object {
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()

        /** Request only the props the layout needs (§6): etag, content-length, resourcetype. */
        val PROPFIND_BODY: String =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:getetag/>
                <d:getcontentlength/>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>
            """.trimIndent()
    }
}
