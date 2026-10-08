package org.openwebdav.messenger.membership

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.directory.DirectoryTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PrivateMembershipPublisherTest {
    private lateinit var server: MockWebServer
    private lateinit var store: PendingPrivateClaimStore
    private lateinit var identity: org.openwebdav.messenger.identity.Identity
    private val key = org.openwebdav.messenger.crypto.ChatKey.fromBytes(ByteArray(32) { 7 })
    private val writes = mutableListOf<ByteArray>()
    private val putCount = AtomicInteger()

    @Before
    fun setUp() {
        val native = DirectoryTestSupport.native()
        store = PendingPrivateClaimStore(ApplicationProvider.getApplicationContext(), native).also { it.clearAll() }
        identity = IdentityTestSupport.identityCrypto().generateIdentity()
        server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.method) {
                                "MKCOL" -> MockResponse().setResponseCode(201)
                                "PUT" -> {
                                    writes += request.body?.clone()?.readByteArray() ?: byteArrayOf()
                                    if (putCount.incrementAndGet() <= 2) {
                                        MockResponse().setResponseCode(500)
                                    } else {
                                        MockResponse().setResponseCode(201)
                                    }
                                }
                                else -> MockResponse().setResponseCode(500)
                            }
                    }
                start()
            }
    }

    @After
    fun tearDown() {
        server.shutdown()
        store.clearAll()
    }

    @Test
    fun failed_upload_keeps_exact_claim_for_successful_retry() =
        runTest {
            val idCrypto = IdentityTestSupport.identityCrypto()
            val claimCrypto =
                PrivateMembershipClaimCrypto(
                    PrivateMembershipAead(Aead(DirectoryTestSupport.native())),
                    PrivateMembershipClaimCodec(idCrypto),
                )
            val publisher = PrivateMembershipPublisher(store, claimCrypto)
            val service = PrivateMembershipService(DirectoryTestSupport.transport(server), claimCrypto)
            val first = publisher.publish(ChatAccess.PRIVATE, "community-a", "chat-a", "Alice", identity, key, service)
            val pending = store.load("community-a", "chat-a", "private", key, identity)

            assertEquals(PrivateClaimPublicationStatus.PENDING, first)
            assertEquals(false, pending?.uploaded)
            assertEquals(
                PrivateClaimPublicationStatus.UPLOADED,
                publisher.publish(ChatAccess.PRIVATE, "community-a", "chat-a", "Alice", identity, key, service),
            )
            val uploaded = store.load("community-a", "chat-a", "private", key, identity)
            assertEquals(true, uploaded?.uploaded)
            assertArrayEquals(writes.first(), writes.last())
            assertArrayEquals(pending?.fileBytes, uploaded?.fileBytes)
        }
}
