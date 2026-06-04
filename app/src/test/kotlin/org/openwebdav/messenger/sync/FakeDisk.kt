package org.openwebdav.messenger.sync

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * An in-memory fake WebDAV disk backing a [okhttp3.mockwebserver.MockWebServer] for the sync poll
 * tests. It implements just enough of RFC 4918 for the §6 access rules the poll uses: `PROPFIND
 * Depth: 1` (collection listing), `GET` (file body), `PUT` (store), `MKCOL` (collection), `DELETE`.
 *
 * Files are keyed by chat-root-relative path (`log/<name>`, `changes/<id>/<entry>`). A PROPFIND on a
 * collection returns a multistatus listing its direct member files (the collection self-entry plus one
 * `<response>` per file), shaped exactly as [org.openwebdav.messenger.transport.PropfindParser] reads
 * (the entry name is the href's last path segment).
 *
 * Per-path failure injection ([failPropfind] / [failGet]) lets a test simulate a `429` mid-fetch to
 * assert the §9.3 cursor-not-advanced invariant.
 */
internal class FakeDisk(private val chatRoot: String = SyncTestSupport.CHAT_ROOT) : Dispatcher() {
    private val files = LinkedHashMap<String, ByteArray>()
    private val collections = LinkedHashSet<String>()

    /** Relative paths whose PROPFIND should return [code] (e.g. 429) instead of a listing. */
    val failPropfind = HashMap<String, Int>()

    /** Relative paths whose GET should return [code] (e.g. 429) instead of the body. */
    val failGet = HashMap<String, Int>()

    /** Collection-path prefixes whose PUT should return [code] (e.g. 429) — partial-failure injection. */
    val failPutUnderPrefix = HashMap<String, Int>()

    /** Record of every PROPFIND `Depth` header seen (for the propfind_uses_depth_1 stack test). */
    val propfindDepths = mutableListOf<String?>()

    fun putFile(
        relativePath: String,
        bytes: ByteArray,
    ) {
        files[relativePath] = bytes
        ensureParents(relativePath)
    }

    fun fileNames(collection: String): List<String> =
        files.keys.filter { it.startsWith("$collection/") && it.removePrefix("$collection/").none { c -> c == '/' } }
            .map { it.substringAfterLast('/') }

    fun has(relativePath: String): Boolean = files.containsKey(relativePath)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val rel = relativePath(request.path ?: "") ?: return MockResponse().setResponseCode(400)
        return when (request.method) {
            "PROPFIND" -> {
                propfindDepths.add(request.getHeader("Depth"))
                propfind(rel)
            }
            "GET" -> get(rel)
            "PUT" -> put(rel, request.body.readByteArray())
            "MKCOL" -> mkcol(rel)
            "DELETE" -> delete(rel)
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun propfind(rel: String): MockResponse {
        failPropfind[rel]?.let { return MockResponse().setResponseCode(it) }
        val members = fileNames(rel)
        val body = multistatus(rel, members)
        return MockResponse().setResponseCode(207).setBody(body).addHeader("Content-Type", "application/xml; charset=utf-8")
    }

    private fun get(rel: String): MockResponse {
        failGet[rel]?.let { return MockResponse().setResponseCode(it) }
        val bytes = files[rel] ?: return MockResponse().setResponseCode(404)
        return MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes))
    }

    private fun put(
        rel: String,
        bytes: ByteArray,
    ): MockResponse {
        failPutUnderPrefix.entries.firstOrNull { rel.startsWith(it.key) }?.let {
            return MockResponse().setResponseCode(it.value)
        }
        files[rel] = bytes
        ensureParents(rel)
        return MockResponse().setResponseCode(201)
    }

    private fun mkcol(rel: String): MockResponse {
        // Idempotent: an already-existing collection returns 405 (the transport treats it as success).
        if (collections.contains(rel)) return MockResponse().setResponseCode(405)
        collections.add(rel)
        return MockResponse().setResponseCode(201)
    }

    private fun delete(rel: String): MockResponse {
        files.remove(rel)
        return MockResponse().setResponseCode(204)
    }

    private fun ensureParents(relativePath: String) {
        var idx = relativePath.indexOf('/')
        while (idx != -1) {
            collections.add(relativePath.substring(0, idx))
            idx = relativePath.indexOf('/', idx + 1)
        }
    }

    /** Strip the leading `/<chatRoot>/` from a request path → chat-root-relative path. */
    private fun relativePath(requestPath: String): String? {
        val prefix = "/$chatRoot/"
        val noQuery = requestPath.substringBefore('?')
        if (!noQuery.startsWith(prefix)) return null
        return noQuery.removePrefix(prefix).trimEnd('/')
    }

    private fun multistatus(
        collection: String,
        members: List<String>,
    ): String {
        val memberXml =
            members.joinToString("\n") { name ->
                """
                <d:response>
                  <d:href>/$chatRoot/$collection/$name</d:href>
                  <d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop>
                    <d:getetag>"etag-$name"</d:getetag>
                    <d:getcontentlength>${files["$collection/$name"]?.size ?: 0}</d:getcontentlength>
                    <d:resourcetype/>
                  </d:prop></d:propstat>
                </d:response>
                """.trimIndent()
            }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/$chatRoot/$collection/</d:href>
                <d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              $memberXml
            </d:multistatus>
            """.trimIndent()
    }
}
