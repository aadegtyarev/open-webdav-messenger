package org.openwebdav.messenger.governance

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.transport.TestSupport
import java.util.concurrent.atomic.AtomicReference

class CommunityDmOriginServiceTest {
    @Test
    fun new_dm_publishes_initiator_origin_and_repeat_reads_it() =
        runTest {
            val server = MockWebServer()
            val storedBytes = AtomicReference<ByteArray>()
            val storedHash = AtomicReference<String?>()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.method) {
                            "MKCOL" -> MockResponse().setResponseCode(201)
                            "PUT" -> {
                                storedBytes.set(request.body.readByteArray())
                                storedHash.set(request.requestUrl!!.encodedPathSegments.last())
                                MockResponse().setResponseCode(201)
                            }
                            "PROPFIND" -> {
                                val files = storedHash.get()?.let { arrayOf(it to "etag") } ?: emptyArray()
                                MockResponse()
                                    .setResponseCode(207)
                                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                                    .setBody(TestSupport.multistatus("governance/origins/dm", *files))
                            }
                            "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(storedBytes.get()))
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            try {
                val transport = TestSupport.newTransport(server)
                val identityCrypto = IdentityTestSupport.identityCrypto()
                val initiator = identityCrypto.generateIdentity()
                val peer = identityCrypto.generateIdentity()
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 6 })
                val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val reader = CommunityGovernanceReader(transport, envelopes)
                val writer = CommunityGovernanceWriter(transport, envelopes)
                val service = CommunityDmOriginService(reader, writer, envelopes, identityCrypto)
                val participants =
                    listOf(
                        CommunityDmParticipant(initiator.copySignPublic(), initiator.copyBoxPublic()),
                        CommunityDmParticipant(peer.copySignPublic(), peer.copyBoxPublic()),
                    )

                val created = service.ensureOrPublish("community-1", "dm-chat-1", participants, initiator, key)
                assertTrue(created is CommunityDmOriginService.Result.Ready && created.published)
                storedHash.set((created as CommunityDmOriginService.Result.Ready).originHash)
                val existing = service.ensureOrPublish("community-1", "dm-chat-1", participants.reversed(), peer, key)
                assertTrue(existing is CommunityDmOriginService.Result.Ready)
                assertFalse((existing as CommunityDmOriginService.Result.Ready).published)
                val scanned = CommunityDmOriginScanner(reader, envelopes, identityCrypto).inspectOrigin("community-1", "dm-chat-1", key)
                assertTrue(
                    scanned is CommunityDmOriginScanner.Result.Found &&
                        scanned.origin.initiatorSigningPublicKey.contentEquals(initiator.copySignPublic()),
                )
                assertTrue(storedBytes.get().isNotEmpty())
                val otherPeer = identityCrypto.generateIdentity()
                val conflicting =
                    service.ensureOrPublish(
                        "community-1",
                        "dm-chat-1",
                        listOf(participants.first(), CommunityDmParticipant(otherPeer.copySignPublic(), otherPeer.copyBoxPublic())),
                        initiator,
                        key,
                    )
                assertTrue(conflicting is CommunityDmOriginService.Result.Conflict)
            } finally {
                server.shutdown()
            }
        }
}
