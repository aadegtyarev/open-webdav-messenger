package org.openwebdav.messenger.chatdirectory

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.directory.SealFailingNative
import org.openwebdav.messenger.identity.IdentityCrypto
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C8: a native AEAD seal failure during [ChatDirectoryService.publishChatEntry] must degrade to the
 * typed [ChatPublishOutcome.Failed] — never propagate an uncaught exception. Reuses the directory
 * suite's [SealFailingNative] (delegates every libsodium call to the real backend except `aeadEncrypt`,
 * which throws the native-failure `IllegalStateException`). New test file; the existing chat-directory
 * suites stay untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatDirectorySealFailureTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: ChatDirectoryFakeDisk

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = ChatDirectoryFakeDisk()
        server.dispatcher = disk
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serviceWith(native: NativeCrypto): ChatDirectoryService {
        val crypto = ChatDirectoryCrypto.create(MessageCrypto(Aead(native)), IdentityCrypto(native))
        return ChatDirectoryService(ChatDirectoryTestSupport.transport(server), crypto)
    }

    // publish_degrades_on_native_seal_failure — a seal-failing native makes publishChatEntry return
    // Failed (not RejectedDm, not a crash) for a valid GROUP descriptor. No exception escapes.
    @Test
    fun publish_degrades_on_native_seal_failure() =
        runTest {
            val service = serviceWith(SealFailingNative(ChatDirectoryTestSupport.native()))
            val outcome =
                service.publishChatEntry(
                    identity = ChatDirectoryTestSupport.newIdentity(),
                    chatId = "chat-1".toByteArray(),
                    kind = ChatKind.GROUP,
                    access = ChatAccess.PUBLIC,
                    title = "Town Square",
                    versionCounter = 1,
                    communityKey = ChatDirectoryTestSupport.communityKey(),
                )
            assertTrue("a native seal failure must map to the typed Failed", outcome is ChatPublishOutcome.Failed)
            assertTrue("no descriptor written on a seal failure", disk.fileNames(ChatDirectoryPaths.CHAT_DIRECTORY).isEmpty())
        }

    // publish_fails_leaves_read_loop_usable — after an induced seal failure on publish, a subsequent
    // read on the SAME service still verifies a pre-existing descriptor (the open/aeadDecrypt path is
    // delegated to the real backend; no corrupted crypto state, wipe-in-finally ran).
    @Test
    fun publish_fails_leaves_read_loop_usable() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val good =
                ChatDirectoryTestSupport.sealDescriptor(
                    identity = ChatDirectoryTestSupport.newIdentity(),
                    chatId = "chat-existing".toByteArray(),
                    access = ChatAccess.PUBLIC,
                    title = "Already-There",
                    versionCounter = 1,
                    communityKey = key,
                )
            disk.putFile(ChatDirectoryPaths.entryPath(good.name), good.bytes)

            val service = serviceWith(SealFailingNative(ChatDirectoryTestSupport.native()))
            val failed =
                service.publishChatEntry(
                    identity = ChatDirectoryTestSupport.newIdentity(),
                    chatId = "chat-will-fail".toByteArray(),
                    kind = ChatKind.GROUP,
                    access = ChatAccess.PUBLIC,
                    title = "Will-Fail",
                    versionCounter = 1,
                    communityKey = key,
                )
            assertTrue(failed is ChatPublishOutcome.Failed)

            val read = service.readChatDirectory(key)
            assertEquals(listOf("Already-There"), read.entries.map { it.title })
        }
}
