package org.openwebdav.messenger.chatdirectory

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.BackOffPolicy
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.WebDavTransport
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Real, libsodium-backed substrates + a [MockWebServer]-backed transport for the §11 chat-directory JVM
 * tests. lazysodium-java loads the host's system libsodium (the same code the app runs via
 * lazysodium-android behind [NativeCrypto]); the WebDAV transport talks to a MockWebServer backed by
 * [ChatDirectoryFakeDisk] — so the full publish/read round-trip is exercised off-device exactly as the
 * plan's Test plan requires. Mirrors `directory/DirectoryTestSupport`.
 */
internal object ChatDirectoryTestSupport {
    const val COMMUNITY_ROOT = "community-root"

    private val sodium: LazySodiumJava by lazy { LazySodiumJava(SodiumJava()) }

    fun native(): NativeCrypto = LazySodiumCrypto(sodium)

    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native())

    fun newIdentity(): Identity = identityCrypto().generateIdentity()

    fun messageCrypto(): MessageCrypto = MessageCrypto(Aead(native()))

    fun chatDirectoryCrypto(): ChatDirectoryCrypto = ChatDirectoryCrypto.create(messageCrypto(), identityCrypto())

    fun codec(): ChatDescriptorCodec = ChatDescriptorCodec(identityCrypto())

    /** A deterministic 32-byte community key (the community-wide symmetric AEAD key, §11.2). */
    fun communityKey(seed: Byte = 42): ChatKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { seed })

    /** A short-timeout OkHttp client so timeout-retry tests fail fast (mirrors the transport/directory tests). */
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
            chatRoot = COMMUNITY_ROOT,
        )

    fun transport(server: MockWebServer): WebDavTransport =
        WebDavTransport(
            config = config(server),
            client = client(),
            backOff = BackOffPolicy(maxRetries = 1, baseDelayMillis = 1L, maxDelayMillis = 2L),
            delayer = { },
        )

    fun service(server: MockWebServer): ChatDirectoryService = ChatDirectoryService(transport(server), chatDirectoryCrypto())

    /**
     * Seal a group chat descriptor directly (bypassing [ChatDirectoryService.publishChatEntry]) so a
     * test can mint an entry with arbitrary fields (a chosen version-counter, a tampered byte, a wrong
     * community key) and place it on the [ChatDirectoryFakeDisk]. Returns the envelope bytes + the §11.4
     * content name.
     */
    fun sealDescriptor(
        identity: Identity,
        chatId: ByteArray,
        access: ChatAccess,
        title: String,
        versionCounter: Long,
        communityKey: ChatKey,
        kind: ChatKind = ChatKind.GROUP,
    ): SealedEntry {
        val bytes =
            chatDirectoryCrypto().sealDescriptor(
                chatId = chatId,
                kind = kind,
                access = access,
                title = title,
                signingPublic = identity.copySignPublic(),
                versionCounter = versionCounter,
                signingSecret = identity.copySignSecret(),
                communityKey = communityKey,
            )
        return SealedEntry(name = ChatDirectoryPaths.entryName(bytes), bytes = bytes)
    }

    /**
     * Build + sign a §11.3 inner payload with a CALLER-CHOSEN `kind`/`access` raw byte, then seal it
     * under [communityKey] — used to forge a `dm`-kind entry (a validly sealed + signed payload whose
     * kind byte the §11.5 read-time gate must drop) or an out-of-enum access entry that bypasses the
     * publish-side guard. Mirrors the hand-built-payload helper of `DirectoryRejectionTest`.
     */
    fun sealRawDescriptor(
        identity: Identity,
        chatId: ByteArray,
        kindByte: Int,
        accessByte: Int,
        title: String,
        versionCounter: Long,
        communityKey: ChatKey,
    ): SealedEntry {
        val signed = buildInner(identity.copySignPublic(), versionCounter, kindByte, accessByte, chatId, title.toByteArray(Charsets.UTF_8))
        val sig = identityCrypto().sign(signed, identity.copySignSecret())
        val inner = signed + sig
        val bytes = messageCrypto().sealEnvelope(communityKey, inner)
        return SealedEntry(name = ChatDirectoryPaths.entryName(bytes), bytes = bytes)
    }

    private fun buildInner(
        signingPub: ByteArray,
        versionCounter: Long,
        kindByte: Int,
        accessByte: Int,
        chatId: ByteArray,
        titleBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ChatDescriptorFormat.ENTRY_VERSION.toInt())
        out.write(signingPub)
        for (i in 7 downTo 0) out.write(((versionCounter ushr (8 * i)) and 0xFF).toInt())
        out.write(kindByte and 0xFF)
        out.write(accessByte and 0xFF)
        out.write((chatId.size ushr 8) and 0xFF)
        out.write(chatId.size and 0xFF)
        out.write(chatId)
        out.write((titleBytes.size ushr 8) and 0xFF)
        out.write(titleBytes.size and 0xFF)
        out.write(titleBytes)
        return out.toByteArray()
    }

    data class SealedEntry(val name: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is SealedEntry && name == other.name && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = name.hashCode()
    }
}
