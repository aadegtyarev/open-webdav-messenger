package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.governance.CommunityGovernanceEnvelopeCodec
import org.openwebdav.messenger.governance.CommunityGovernanceReader
import org.openwebdav.messenger.governance.CommunityGroupOrigin
import org.openwebdav.messenger.governance.CommunityGroupOriginCodec
import org.openwebdav.messenger.governance.GovernanceObjectKind
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.transport.TestSupport

class ChatDeletionAuthorityProjectionTest {
    @Test
    fun equivocal_group_origin_hides_member_action_but_verified_host_can_delete() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                val crypto = IdentityTestSupport.identityCrypto()
                val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 8 })
                val creator = crypto.generateIdentity()
                val otherCreator = crypto.generateIdentity()
                val records =
                    listOf(creator, otherCreator).mapIndexed { index, identity ->
                        val origin =
                            CommunityGroupOrigin(
                                "community-1",
                                "group-1",
                                if (index == 0) ChatAccess.PUBLIC else ChatAccess.PRIVATE,
                                identity.copySignPublic(),
                                identity.copyBoxPublic(),
                                ByteArray(32) { index.toByte() },
                            )
                        val signed = CommunityGroupOriginCodec.sign(origin, identity.copySignSecret(), crypto)
                        val stored = envelopes.seal(GovernanceObjectKind.GROUP_ORIGIN, "community-1", key, signed)
                        envelopes.contentHash(stored) to stored
                    }.toMap()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.method) {
                                "PROPFIND" ->
                                    MockResponse().setResponseCode(207)
                                        .setHeader("Content-Type", "application/xml; charset=utf-8")
                                        .setBody(
                                            TestSupport.multistatus(
                                                "governance/origins/group",
                                                *records.keys.map { it to "etag" }.toTypedArray(),
                                            ),
                                        )
                                "GET" ->
                                    MockResponse().setResponseCode(200)
                                        .setBody(okio.Buffer().write(records.getValue(request.requestUrl!!.encodedPathSegments.last())))
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                val projector =
                    ChatDeletionAuthorityProjector(
                        CommunityGovernanceReader(TestSupport.newTransport(server), envelopes),
                        envelopes,
                        crypto,
                    )
                assertEquals(
                    ChatDeleteCapability.Unavailable,
                    projector.project("community-1", "group-1", "group", creator.copySignPublic(), false, null, null),
                )
                assertEquals(
                    ChatDeleteCapability.NotAuthorized,
                    projector.project("community-1", "group-1", "group", creator.copySignPublic(), false, key, null),
                )
                assertEquals(
                    ChatDeleteCapability.Available,
                    projector.project("community-1", "group-1", "group", creator.copySignPublic(), true, key, null),
                )
            } finally {
                server.shutdown()
            }
        }
}
