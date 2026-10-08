package org.openwebdav.messenger.membership

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
        first.save("community", identity, "chat-42")

        assertEquals("chat-42", PrivateClaimRetryCursorStore(context).load("community", identity))
        first.clearAll()
    }
}
