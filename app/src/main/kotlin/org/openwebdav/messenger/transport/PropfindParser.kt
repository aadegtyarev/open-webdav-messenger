package org.openwebdav.messenger.transport

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a WebDAV `multistatus` PROPFIND response (RFC 4918 §9.1) into [InboxEntry] rows.
 *
 * Extracts `d:getetag`, `d:getcontentlength`, and `d:resourcetype/d:collection` per
 * `docs/protocol/webdav-layout.md` §6 ("Capture the ETag from d:getetag"). Namespace-prefix
 * agnostic: matches on local element names (`response`, `href`, `getetag`, …) regardless of the
 * `d:` / `D:` prefix a provider uses.
 *
 * Uses `javax.xml.parsers` (DOM), available on both the JVM (for MockWebServer tests) and Android.
 * Entity/DTD processing is disabled (XXE hardening) since the body comes from an untrusted disk.
 *
 * The collection at the listed path itself appears as one `response`; the caller filters by name.
 */
internal object PropfindParser {
    /**
     * @param xml the raw multistatus body bytes. Parsed directly so the XML's own declaration /
     *   the DOM parser drives charset decoding — passing pre-decoded text would risk a
     *   double-decode of a non-UTF-8 response (review finding 9).
     * @param basePath the URL path of the listed collection, used to derive each entry's
     *   last-segment [InboxEntry.name].
     */
    fun parse(
        xml: ByteArray,
        basePath: String,
    ): List<InboxEntry> {
        val doc = newDocument(xml) ?: return emptyList()
        val responses = doc.getElementsByTagNameLocal("response")
        return responses.mapNotNull { it as? Element }.map { toEntry(it, basePath) }
    }

    private fun toEntry(
        response: Element,
        basePath: String,
    ): InboxEntry {
        val href = response.firstLocalText("href")
        // RFC 4918 §9.1: a server MAY split found/not-found props across multiple propstat blocks,
        // each with its own <status>. Select properties only from the block whose status is 200 OK,
        // so a 404 block's empty/garbage etag/length never wins over the real 200 block's value
        // (review finding 5).
        val okPropstats = okPropstats(response)
        val etag = okPropstats.firstNonBlankLocalText("getetag")
        val contentLength = okPropstats.firstNonBlankLocalText("getcontentlength")?.toLongOrNull()
        val isCollection = okPropstats.any { it.getElementsByTagNameLocal("collection").isNotEmpty() }
        return InboxEntry(
            name = lastSegment(href, basePath),
            etag = etag,
            contentLength = contentLength,
            isCollection = isCollection,
        )
    }

    /**
     * The `propstat` children of [response] whose `<status>` line is `200 OK`. A propstat with no
     * status is treated as 200 (some servers omit it for the all-found case); a non-200 status
     * (e.g. `HTTP/1.1 404 Not Found`) is excluded.
     */
    private fun okPropstats(response: Element): List<Element> =
        response.getElementsByTagNameLocal("propstat").mapNotNull { it as? Element }.filter { propstat ->
            val status = propstat.firstLocalText("status")
            status == null || " 200 " in " $status "
        }

    private fun List<Element>.firstNonBlankLocalText(local: String): String? =
        firstNotNullOfOrNull { it.firstLocalText(local)?.ifEmpty { null } }

    private fun newDocument(xml: ByteArray): org.w3c.dom.Document? {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isExpandEntityReferences = false
            }
        // An XML declaration must be the first content; skip any leading ASCII whitespace bytes a
        // server (or a trimIndent() test fixture) may emit before `<?xml`. Whitespace bytes are the
        // same in every ASCII-superset encoding, so this never disturbs charset decoding.
        val start = xml.indexOfFirst { it.toInt() and 0xFF > 0x20 }.coerceAtLeast(0)
        return runCatching {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml, start, xml.size - start))
        }.getOrNull()
    }

    private fun org.w3c.dom.Document.getElementsByTagNameLocal(local: String): List<Node> =
        getElementsByTagName("*").asList().filter { localName(it) == local }

    private fun Element.getElementsByTagNameLocal(local: String): List<Node> =
        getElementsByTagName("*").asList().filter { localName(it) == local }

    private fun Element.firstLocalText(local: String): String? = getElementsByTagNameLocal(local).firstOrNull()?.textContent?.trim()

    private fun localName(node: Node): String = node.localName ?: node.nodeName.substringAfterLast(':')

    private fun org.w3c.dom.NodeList.asList(): List<Node> = (0 until length).map { item(it) }

    private fun lastSegment(
        href: String?,
        basePath: String,
    ): String {
        val raw = (href ?: basePath).trimEnd('/')
        val seg = raw.substringAfterLast('/')
        return java.net.URLDecoder.decode(seg, Charsets.UTF_8.name())
    }
}
