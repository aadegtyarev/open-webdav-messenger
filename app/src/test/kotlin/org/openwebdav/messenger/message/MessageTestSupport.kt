package org.openwebdav.messenger.message

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.protocol.OrderToken

/**
 * Real, libsodium-backed substrates for the §8 message-model JVM tests — lazysodium-java loads the
 * host's system libsodium, so the AEAD seal/open and the Ed25519 sign/verify run for real (the same
 * code the app runs on lazysodium-android, behind [NativeCrypto]).
 */
internal object MessageTestSupport {
    private val sodium: LazySodiumJava by lazy { LazySodiumJava(SodiumJava()) }

    fun native(): NativeCrypto = LazySodiumCrypto(sodium)

    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native())

    fun messageCrypto(): MessageCrypto = MessageCrypto(Aead(native()))

    fun serializer(): MessageSerializer = MessageSerializer(identityCrypto())

    fun parser(): MessageParser = MessageParser(identityCrypto())

    fun envelope(): MessageEnvelope = MessageEnvelope.create(messageCrypto(), identityCrypto())

    fun newIdentity(): Identity = identityCrypto().generateIdentity()

    fun fixedChatKey(seed: Byte = 9): ChatKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { seed })

    /**
     * A well-formed §2 file name (`order-token "~" content-hash`, 62 chars) for use as a `reply-to` /
     * `target-id` REFERENCE to another message — derived deterministically from [seed] bytes so a test
     * can mint distinct, grammar-valid references without sealing first (a message no longer carries a
     * self-id, §8.6; references carry other messages' §2 file names).
     */
    fun messageId(seed: String): String = MessageId.messageId(OrderToken.build(1_000L, seed, 1), seed.toByteArray())

    /** A valid [TextMessage] over a fresh identity, with optional [replyTo] (a §2 file-name reference). */
    fun textMessage(
        identity: Identity,
        body: String = "hello **world**",
        replyTo: String? = null,
        chatId: String = "chat-abc",
        timestamp: Long = 1_717_000_000_000L,
    ): TextMessage =
        TextMessage(
            chatId = chatId,
            sender = identity.publicIdentity(),
            replyTo = replyTo,
            body = body,
            sendTimestampMillis = timestamp,
        )

    /** A valid [ReactionMessage] over a fresh identity; [targetSeed] mints the §2 file-name reference. */
    fun reactionMessage(
        identity: Identity,
        targetSeed: String = "target-msg",
        index: Int = 3,
        chatId: String = "chat-abc",
    ): ReactionMessage =
        ReactionMessage(
            chatId = chatId,
            sender = identity.publicIdentity(),
            targetId = messageId(targetSeed),
            reactionIndex = index,
        )
}
