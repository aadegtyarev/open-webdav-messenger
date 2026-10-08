package org.openwebdav.messenger.membership

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryFakeDisk
import org.openwebdav.messenger.directory.DirectoryTestSupport
import org.openwebdav.messenger.identity.IdentityCrypto

class PrivateMembershipServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: PrivateMembershipService
    private lateinit var identity: org.openwebdav.messenger.identity.Identity
    private val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 })

    @Before
    fun setUp() {
        server =
            MockWebServer().apply {
                dispatcher = DirectoryFakeDisk()
                start()
            }
        val native = DirectoryTestSupport.native()
        val idCrypto = IdentityCrypto(native)
        identity = idCrypto.generateIdentity()
        val claimCrypto = PrivateMembershipClaimCrypto(PrivateMembershipAead(Aead(native)), PrivateMembershipClaimCodec(idCrypto))
        service = PrivateMembershipService(DirectoryTestSupport.transport(server), claimCrypto)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun publish_and_read_is_idempotent_and_exact_chat_scoped() =
        runTest {
            val bytes =
                PrivateMembershipClaimCrypto(
                    PrivateMembershipAead(Aead(DirectoryTestSupport.native())),
                    PrivateMembershipClaimCodec(DirectoryTestSupport.identityCrypto()),
                ).seal("chat_01", "Alice", identity, key)
            assertTrue(service.publishSelf(bytes, "chat_01") is MembershipPublishOutcome.Published)
            assertTrue(service.publishSelf(bytes, "chat_01") is MembershipPublishOutcome.Published)
            val result = service.read("chat_01", key, null)
            assertEquals(false, result.listingFailed)
            assertEquals(listOf("Alice"), result.members.map { it.displayName })
            assertTrue(service.read("other", key, null).members.isEmpty())
        }
}
