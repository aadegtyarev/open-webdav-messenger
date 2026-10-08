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
class PrivateMembershipPublisherInvalidationTest {
    private val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 31 })
    private val identity = IdentityTestSupport.identityCrypto().generateIdentity()

    @Test
    fun restore_during_suspended_put_cannot_repopulate_pending_state() =
        runTest {
            val (store, publisher, barrier) = setup()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer =
                PrivateClaimWriter { _, _ ->
                    started.complete(Unit)
                    release.await()
                    MembershipPublishOutcome.Published
                }
            val operation = async { publisher.publish(ChatAccess.PRIVATE, "community", "chat", "A", identity, key, writer) }
            started.await()
            barrier.withAccountReplacement { store.clearAll() }
            release.complete(Unit)

            assertEquals(PrivateClaimPublicationStatus.PENDING, operation.await())
            assertNull(store.load("community", "chat", "private", key, identity))
        }

    @Test
    fun key_replacement_during_suspended_put_cannot_commit_old_claim() =
        runTest {
            val (store, publisher, barrier) = setup()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var current = true
            val writer =
                PrivateClaimWriter { _, _ ->
                    started.complete(Unit)
                    release.await()
                    MembershipPublishOutcome.Published
                }
            val operation =
                async {
                    publisher.publish(ChatAccess.PRIVATE, "community", "chat", "A", identity, key, writer, contextCurrent = { current })
                }
            started.await()
            barrier.withAccountReplacement {
                current = false
                store.invalidate("community", "chat", identity)
            }
            release.complete(Unit)

            assertEquals(PrivateClaimPublicationStatus.PENDING, operation.await())
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
