package org.openwebdav.messenger.governance

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.TestSupport

internal object CommunityCreationPolicyTestSupport {
    const val CHAT_ID = "abcdefghijklmnopqrstuvwxyz234567"

    fun administration(
        reader: CommunityGovernanceReader,
        writer: CommunityGovernanceWriter,
        key: ChatKey,
        host: Identity,
        crypto: IdentityCrypto,
        accept: (Long, String) -> PolicyRevisionDecision,
    ) = CommunityCreationPolicyAdministration(
        reader, writer, "community-1", key, host.copySignPublic(), host.copySignSecret(), 0, null, crypto, accept,
    )

    fun dispatcher(
        originHash: String?,
        originBytes: ByteArray?,
        policyUpload: (ByteArray) -> Unit,
        failPut: Boolean = false,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "PUT" ->
                    if (failPut) {
                        MockResponse().setResponseCode(500)
                    } else {
                        policyUpload(request.body.readByteArray())
                        MockResponse().setResponseCode(201)
                    }
                "PROPFIND" -> {
                    val group = request.requestUrl!!.encodedPath.contains("origins/group")
                    val files = if (group && originHash != null) arrayOf(originHash to "etag") else emptyArray()
                    MockResponse().setResponseCode(207)
                        .setHeader("Content-Type", "application/xml; charset=utf-8")
                        .setBody(TestSupport.multistatus(if (group) "governance/origins/group" else "governance/policies", *files))
                }
                "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(checkNotNull(originBytes)))
                else -> MockResponse().setResponseCode(404)
            }
    }
}
