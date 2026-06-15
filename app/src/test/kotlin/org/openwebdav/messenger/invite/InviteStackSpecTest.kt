package org.openwebdav.messenger.invite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Stack-spec test for the invite codec (`ui-chat-surface` plan Stack expectations: Kotlin off-main-thread —
 * "invite encode/decode (gzip via stdlib `java.util.zip`, base64url) runs off the UI thread").
 * Source: <https://kotlinlang.org/docs/coroutines-and-channels.html>
 *
 * `invite_codec_off_ui_dispatcher`: the codec hops the framing work onto its injected IO dispatcher — proven
 * by injecting a tracking dispatcher and asserting it was dispatched to (the work did not stay inline on the
 * caller).
 */
class InviteStackSpecTest {
    /** A dispatcher that records whether the codec dispatched work onto it. */
    private class TrackingDispatcher : CoroutineDispatcher() {
        val dispatched = AtomicBoolean(false)
        private val delegate = Executors.newSingleThreadExecutor()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatched.set(true)
            delegate.execute(block)
        }
    }

    /** invite_codec_off_ui_dispatcher — encode AND decode dispatch their work onto the injected dispatcher. */
    @Test
    fun invite_codec_off_ui_dispatcher() =
        runTest {
            val tracker = TrackingDispatcher()
            val codec = InviteCodec(ioDispatcher = tracker)
            val token =
                InviteToken(
                    baseUrl = "https://disk.example.test",
                    username = "u",
                    appPassword = "fake-pw-not-real",
                    chatRoot = "r",
                    chatId = "c",
                    chatKey = ByteArray(InviteToken.CHAT_KEY_BYTES) { 3 },
                    communityName = "n",
                )

            val encoded = codec.encode(token)
            assertTrue("encode must dispatch off the caller onto the IO dispatcher", tracker.dispatched.get())

            tracker.dispatched.set(false)
            val decoded = codec.decode(encoded)
            assertTrue("decode must dispatch off the caller onto the IO dispatcher", tracker.dispatched.get())
            assertEquals(token, (decoded as InviteCodec.Result.Decoded).token)
        }
}
