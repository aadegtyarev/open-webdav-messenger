package org.openwebdav.messenger.membership

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateClaimRetryCursorStoreTest {
    @Test
    fun cursor_survives_store_recreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val identity = IdentityTestSupport.identityCrypto().generateIdentity()
        val first = PrivateClaimRetryCursorStore(context)
        first.clearAll()
        first.savePending("community", identity, "chat-pending")
        first.saveFresh("community", identity, "chat-fresh")

        assertEquals(
            PrivateClaimRetryCursors("chat-pending", "chat-fresh"),
            PrivateClaimRetryCursorStore(context).load("community", identity),
        )
        first.clearAll()
    }

    @Test
    fun legacy_shared_cursor_migrates_to_both_queues_and_corrupt_v2_fails_closed() {
        assertEquals(
            PrivateClaimRetryCursors("legacy-chat", "legacy-chat"),
            PrivateClaimRetryCursorCodec.decode("legacy-chat"),
        )
        assertNull(PrivateClaimRetryCursorCodec.decode("v2\nbad/id\n"))
        assertNull(PrivateClaimRetryCursorCodec.decode("v2\nchat-a\nchat-b\nextra"))
        assertEquals(
            PrivateClaimRetryCursors("pending-chat", "fresh-chat"),
            PrivateClaimRetryCursorCodec.decode(
                PrivateClaimRetryCursorCodec.encode(PrivateClaimRetryCursors("pending-chat", "fresh-chat")),
            ),
        )
    }
}
