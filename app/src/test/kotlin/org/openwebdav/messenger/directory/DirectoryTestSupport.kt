package org.openwebdav.messenger.directory

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
import java.util.concurrent.TimeUnit

/**
 * Real, libsodium-backed substrates + a [MockWebServer]-backed transport for the §10 directory JVM
 * tests. lazysodium-java loads the host's system libsodium (the same code the app runs via
 * lazysodium-android behind [NativeCrypto]); the WebDAV transport talks to a MockWebServer backed by
 * [DirectoryFakeDisk] — so the full publish/read round-trip is exercised off-device exactly as the
 * plan's Test plan requires. Mirrors `sync/SyncTestSupport`.
 */
internal object DirectoryTestSupport {
    const val COMMUNITY_ROOT = "community-root"

    private val sodium: LazySodiumJava by lazy { LazySodiumJava(SodiumJava()) }

    fun native(): NativeCrypto = LazySodiumCrypto(sodium)

    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native())

    fun newIdentity(): Identity = identityCrypto().generateIdentity()

    fun directoryCrypto(): DirectoryCrypto = DirectoryCrypto.create(MessageCrypto(Aead(native())), identityCrypto())

    /** A deterministic 32-byte community key (the community-wide symmetric AEAD key, §10.2). */
    fun communityKey(seed: Byte = 42): ChatKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { seed })

    /** A short-timeout OkHttp client so timeout-retry tests fail fast (mirrors the transport/sync tests). */
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

    fun service(server: MockWebServer): DirectoryService = DirectoryService(transport(server), directoryCrypto())

    /**
     * Seal a directory entry directly (bypassing [DirectoryService.publishEntry]) so a test can mint an
     * entry with arbitrary fields (e.g. a chosen version-counter, a tampered byte, a wrong community
     * key) and place it on the [DirectoryFakeDisk]. Returns the envelope bytes + the §10.4 content name.
     */
    fun sealEntry(
        identity: Identity,
        displayName: String,
        versionCounter: Long,
        communityKey: ChatKey,
    ): SealedEntry {
        val bytes =
            directoryCrypto().sealEntry(
                displayName = displayName,
                signingPublic = identity.copySignPublic(),
                boxPublic = identity.copyBoxPublic(),
                versionCounter = versionCounter,
                signingSecret = identity.copySignSecret(),
                communityKey = communityKey,
            )
        return SealedEntry(name = DirectoryPaths.entryName(bytes), bytes = bytes)
    }

    data class SealedEntry(val name: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is SealedEntry && name == other.name && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = name.hashCode()
    }
}
