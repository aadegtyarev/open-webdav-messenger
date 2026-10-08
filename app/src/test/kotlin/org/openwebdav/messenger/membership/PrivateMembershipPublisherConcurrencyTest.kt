package org.openwebdav.messenger.membership

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.DirectoryTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateMembershipPublisherConcurrencyTest {
    private val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 31 })
    private val identity = IdentityTestSupport.identityCrypto().generateIdentity()

    @Test
    fun concurrent_foreground_and_background_attempts_share_one_upload() =
        runTest {
            val (store, publisher, barrier) = setup()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val writer =
                PrivateClaimWriter { _, _ ->
                    calls++
                    started.complete(Unit)
                    release.await()
                    MembershipPublishOutcome.Published
                }
            val first = async { publisher.publish(ChatAccess.PRIVATE, "community", "chat", "A", identity, key, writer) }
            started.await()
            val second = async { publisher.publish(ChatAccess.PRIVATE, "community", "chat", "A", identity, key, writer) }
            kotlinx.coroutines.yield()
            assertEquals(1, calls)
            release.complete(Unit)

            assertEquals(PrivateClaimPublicationStatus.UPLOADED, first.await())
            assertEquals(PrivateClaimPublicationStatus.UPLOADED, second.await())
            assertEquals(1, calls)
            assertEquals(0L, barrier.replacementGeneration())
        }

    @Test
    fun stale_context_is_rejected_before_remote_start_or_pending_save() =
        runTest {
            val (store, publisher, _) = setup()
            var calls = 0
            val writer =
                PrivateClaimWriter { _, _ ->
                    calls++
                    MembershipPublishOutcome.Published
                }

            assertEquals(
                PrivateClaimPublicationStatus.PENDING,
                publisher.publish(ChatAccess.PRIVATE, "community", "chat", "A", identity, key, writer, contextCurrent = { false }),
            )
            assertEquals(0, calls)
            assertNull(store.load("community", "chat", "private", key, identity))
        }

    private fun setup(): Triple<PendingPrivateClaimStore, PrivateMembershipPublisher, AccountMutationBarrier> {
        val native = DirectoryTestSupport.native()
        val store = PendingPrivateClaimStore(ApplicationProvider.getApplicationContext(), native).also { it.clearAll() }
        val barrier = AccountMutationBarrier()
        val claim =
            PrivateMembershipClaimCrypto(
                PrivateMembershipAead(Aead(native)),
                PrivateMembershipClaimCodec(IdentityTestSupport.identityCrypto()),
            )
        return Triple(store, PrivateMembershipPublisher(store, claim, barrier), barrier)
    }
}
