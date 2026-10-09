package org.openwebdav.messenger.governance

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.transport.TestSupport
import org.openwebdav.messenger.transport.WebDavResult

class CommunityGroupOriginCodecTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun origin_signature_binds_community_chat_access_and_creator_keys() {
        val creator = crypto.generateIdentity()
        val origin =
            CommunityGroupOrigin(
                "community-1",
                "abcdefghijklmnopqrstuvwxyz234567",
                ChatAccess.PRIVATE,
                creator.copySignPublic(),
                creator.copyBoxPublic(),
                ByteArray(32) { 9 },
            )
        val bytes = CommunityGroupOriginCodec.sign(origin, creator.copySignSecret(), crypto)
        val verified = CommunityGroupOriginCodec.verify(bytes, origin.communityId, origin.chatId, crypto)
        val contextFreeVerified = CommunityGroupOriginCodec.verify(bytes, null, null, crypto)

        assertEquals(origin.chatId, contextFreeVerified?.chatId)
        assertEquals(ChatAccess.PRIVATE, verified?.access)
        assertTrue(verified!!.creatorSigningPublicKey.contentEquals(origin.creatorSigningPublicKey))
        assertNull(CommunityGroupOriginCodec.verify(bytes, "other-community", origin.chatId, crypto))
        assertNull(CommunityGroupOriginCodec.verify(bytes, origin.communityId, "bcdefghijklmnopqrstuvwxyz234567a", crypto))
        val tampered = bytes.copyOf().also { it[bytes.lastIndex - 1] = (it.last().toInt() xor 1).toByte() }
        assertNull(CommunityGroupOriginCodec.verify(tampered, origin.communityId, origin.chatId, crypto))
    }

    @Test
    fun first_group_origin_lifecycle_succeeds_when_governance_leaf_is_missing() =
        runTest {
            val server = MockWebServer()
            val bytes = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
            val hash = java.util.concurrent.atomic.AtomicReference<String?>()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.method) {
                            "MKCOL" -> MockResponse().setResponseCode(201)
                            "PUT" -> {
                                bytes.set(request.body.readByteArray())
                                hash.set(request.requestUrl!!.encodedPathSegments.last())
                                MockResponse().setResponseCode(201)
                            }
                            "PROPFIND" -> {
                                val value = hash.get() ?: return MockResponse().setResponseCode(404)
                                MockResponse().setResponseCode(207)
                                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                                    .setBody(TestSupport.multistatus("governance/origins/group", value to "etag"))
                            }
                            "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(checkNotNull(bytes.get())))
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            try {
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 7 })
                val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val reader = CommunityGovernanceReader(TestSupport.newTransport(server), envelopes)
                assertEquals(WebDavResult.Success(emptyList<String>()), reader.listHashes(GovernanceObjectKind.GROUP_ORIGIN))
                val creator = crypto.generateIdentity()
                assertTrue(
                    CommunityGroupOriginPublisher(CommunityGovernanceWriter(TestSupport.newTransport(server), envelopes), crypto)
                        .publish("community-1", "abcdefghijklmnopqrstuvwxyz234567", ChatAccess.PUBLIC, creator, key),
                )
                assertTrue(
                    CommunityGroupOriginScanner(reader, crypto).inspect("community-1", "abcdefghijklmnopqrstuvwxyz234567", key) is
                        CommunityGroupOriginScanner.Result.Found,
                )
            } finally {
                server.shutdown()
            }
        }
}
