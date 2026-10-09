package org.openwebdav.messenger.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.openwebdav.messenger.transport.TestSupport

internal object ExistingCommunityGenesisTestSupport {
    fun dispatcher(
        hash: String?,
        body: ByteArray?,
        upload: (ByteArray) -> Unit,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "PUT" -> {
                    upload(request.body.readByteArray())
                    MockResponse().setResponseCode(201)
                }
                "PROPFIND" ->
                    MockResponse().setResponseCode(207)
                        .setHeader("Content-Type", "application/xml; charset=utf-8")
                        .setBody(
                            TestSupport.multistatus(
                                "governance/genesis",
                                *if (hash == null) emptyArray() else arrayOf(hash to "etag"),
                            ),
                        )
                "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(checkNotNull(body)))
                else -> MockResponse().setResponseCode(404)
            }
    }
}
