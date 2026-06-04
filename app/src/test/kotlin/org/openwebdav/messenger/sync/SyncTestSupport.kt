package org.openwebdav.messenger.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.message.Message
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.message.TextMessage
import org.openwebdav.messenger.protocol.ChangeEntry
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.protocol.OrderToken
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.WebDavTransport
import java.util.concurrent.TimeUnit

/**
 * Real, libsodium-backed substrates + an in-memory Room DB + a [MockWebServer]-backed transport for
 * the sync JVM tests. lazysodium-java loads the host's system libsodium (the same code the app runs
 * via lazysodium-android behind [NativeCrypto]), Room runs under Robolectric (host SQLite), and the
 * WebDAV transport talks to a MockWebServer — so the full send/poll round-trip is exercised off-device
 * exactly as the plan's Test plan requires.
 */
internal object SyncTestSupport {
    const val CHAT_ROOT = "chat-root"
    const val CHAT_ID = "chat-1"

    private val sodium: LazySodiumJava by lazy { LazySodiumJava(SodiumJava()) }

    fun native(): NativeCrypto = LazySodiumCrypto(sodium)

    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native())

    fun messageEnvelope(): MessageEnvelope = MessageEnvelope.create(MessageCrypto(Aead(native())), identityCrypto())

    fun newIdentity(): Identity = identityCrypto().generateIdentity()

    fun fixedChatKey(seed: Byte = 7): ChatKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { seed })

    /** An in-memory Room DB (Robolectric host SQLite); main-thread queries left disallowed (default). */
    fun inMemoryDb(): MessengerDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MessengerDatabase::class.java,
        ).build()

    fun store(db: MessengerDatabase): MessageStore = MessageStore(db.messageDao(), db.syncCursorDao())

    /** A short-timeout OkHttp client so timeout-retry tests fail fast (mirrors the transport tests). */
    fun client(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .build()

    fun config(server: MockWebServer): ConnectionConfig =
        ConnectionConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "user",
            appPassword = "app-password",
            chatRoot = CHAT_ROOT,
        )

    fun transport(server: MockWebServer): WebDavTransport =
        WebDavTransport(
            config = config(server),
            client = client(),
            backOff = org.openwebdav.messenger.transport.BackOffPolicy(maxRetries = 1, baseDelayMillis = 1L, maxDelayMillis = 2L),
            delayer = { },
        )

    /**
     * Seal [message] under [chatKey] signed by [sender], and return the envelope bytes paired with the
     * full §2 file name (`order-token "~" content-hash`) the sender would `PUT`. [seq]/[ts] drive the
     * order-token so a test can mint a known cursor ordering.
     */
    fun sealedLogEntry(
        message: Message,
        chatKey: ChatKey,
        sender: Identity,
        senderIdentifier: String,
        ts: Long = 1_717_000_000_000L,
        seq: Long = 1,
    ): SealedEntry {
        val envelope = messageEnvelope()
        val bytes = envelope.seal(message, chatKey, sender.copySignSecret())
        val orderToken = OrderToken.build(ts, senderIdentifier, seq)
        val name = MessageId.messageId(orderToken, bytes)
        return SealedEntry(name = name, orderToken = orderToken, bytes = bytes)
    }

    /** A convenience text message over [sender]'s identity. */
    fun text(
        sender: Identity,
        body: String = "hi",
        chatId: String = CHAT_ID,
        replyTo: String? = null,
    ): TextMessage =
        TextMessage(
            chatId = chatId,
            sender = sender.publicIdentity(),
            replyTo = replyTo,
            body = body,
            sendTimestampMillis = 1_717_000_000_000L,
        )

    /** §9.2 change-entry file name for [chatId] at [orderToken]. */
    fun changeEntryName(
        chatId: String,
        orderToken: String,
    ): String = ChangeEntry.name(chatId, orderToken)

    data class SealedEntry(val name: String, val orderToken: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is SealedEntry && name == other.name && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = name.hashCode()
    }
}
