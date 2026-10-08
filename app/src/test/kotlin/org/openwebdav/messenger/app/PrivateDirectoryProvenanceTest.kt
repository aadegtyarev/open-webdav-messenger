package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryFakeDisk
import org.openwebdav.messenger.directory.DirectoryService
import org.openwebdav.messenger.directory.DirectoryTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.membership.MembershipIdentityProvenance
import org.openwebdav.messenger.membership.MembershipPublishOutcome
import org.openwebdav.messenger.membership.PrivateMembershipAead
import org.openwebdav.messenger.membership.PrivateMembershipClaimCodec
import org.openwebdav.messenger.membership.PrivateMembershipClaimCrypto
import org.openwebdav.messenger.membership.PrivateMembershipResolver
import org.openwebdav.messenger.membership.PrivateMembershipService

class PrivateDirectoryProvenanceTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: DirectoryFakeDisk

    @Before
    fun setUp() {
        disk = DirectoryFakeDisk()
        server =
            MockWebServer().apply {
                dispatcher = disk
                start()
            }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun private_key_encrypted_directory_entry_cannot_upgrade_private_claim_provenance() =
        runTest {
            val native = DirectoryTestSupport.native()
            val identityCrypto = IdentityTestSupport.identityCrypto()
            val identity = identityCrypto.generateIdentity()
            val privateKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 41 })
            val maliciousEntry = DirectoryTestSupport.sealEntry(identity, "Forged directory name", 1, privateKey)
            disk.putFile("directory/${maliciousEntry.name}", maliciousEntry.bytes)
            val transport = DirectoryTestSupport.transport(server)
            val directory = DirectoryService(transport, DirectoryTestSupport.directoryCrypto())
            val maliciousRows = directory.readDirectory(privateKey).entries
            assertEquals(1, maliciousRows.size)

            val claims = PrivateMembershipClaimCrypto(PrivateMembershipAead(Aead(native)), PrivateMembershipClaimCodec(identityCrypto))
            val service = PrivateMembershipService(transport, claims)
            val claim = claims.seal("private-chat", "Self-asserted name", identity, privateKey)
            assertEquals(MembershipPublishOutcome.Published, service.publishSelf(claim, "private-chat"))
            assertTrue(disk.fileNames("private-membership/private-chat").isNotEmpty())
            val directoryKey =
                CommunityDirectoryKeyPolicy.resolve(
                    "private-chat",
                    "private-chat",
                    ChatRegistry.Entry("private-chat", "Private", "group", "private"),
                    null,
                    privateKey,
                )
            assertNull(directoryKey)
            val roster = service.read("private-chat", privateKey, directoryKey?.let { directory.readDirectory(it).entries })

            assertFalse(roster.listingFailed)
            assertEquals("Self-asserted name", roster.members.single().displayName)
            assertEquals(MembershipIdentityProvenance.PRIVATE_CHAT_ONLY, roster.members.single().provenance)
            assertEquals(
                MembershipIdentityProvenance.COMMUNITY_DIRECTORY,
                PrivateMembershipResolver.resolve(
                    listOf(
                        claims.open(claim, "private-chat", privateKey).let {
                            (it as org.openwebdav.messenger.membership.ClaimParseResult.Verified).claim
                        },
                    ),
                    maliciousRows,
                )
                    .members.single().provenance,
            )
        }
}
