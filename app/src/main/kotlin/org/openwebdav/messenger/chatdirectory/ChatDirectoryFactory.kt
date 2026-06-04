package org.openwebdav.messenger.chatdirectory

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory

/**
 * Builds the §11 chat-directory substrate on the **Android** backend (`docs/protocol/webdav-layout.md`).
 * `SodiumAndroid` loads the bundled native libsodium `.so` for the device ABI via JNA (a missing ABI =
 * `UnsatisfiedLinkError`, caught by `connectedAndroidTest`; `docs/stack-notes.md` Crypto). JVM unit
 * tests build [ChatDirectoryService] from a `LazySodiumJava`-backed [NativeCrypto] + a MockWebServer
 * transport directly, so this factory is the app-only entry point — mirroring `directory/DirectoryFactory`.
 *
 * The single [LazySodiumAndroid] instance owns the native binding, and the transport reuses the one
 * shared [okhttp3.OkHttpClient] (`TransportFactory.sharedClient`). Construct one [ChatDirectoryFactory]
 * per process and share it.
 */
class ChatDirectoryFactory {
    private val native: NativeCrypto = LazySodiumCrypto(LazySodiumAndroid(SodiumAndroid()))

    /**
     * A [ChatDirectoryService] scoped to [communityRoot] (the §11.1 community-root path supplied in
     * config, NEVER written to disk — SC3/SC4 family). The [baseUrl] + WebDAV credential reach the
     * community's shared space (one credential, Topology A). The **community key** is supplied per call
     * to publish/read (out-of-band / config, §11.2) — it is not held by this factory.
     */
    fun chatDirectoryService(
        baseUrl: String,
        username: String,
        appPassword: String,
        communityRoot: String,
    ): ChatDirectoryService {
        // The §11.1 community-root takes the chat-root slot of the transport's connection config — the
        // transport joins it as the URL path prefix, so the chat directory's PROPFIND/GET/PUT/MKCOL all
        // sit under <base>/<community-root>/chat-directory/ (a distinct sibling of directory/).
        val config =
            ConnectionConfig(
                baseUrl = baseUrl,
                username = username,
                appPassword = appPassword,
                chatRoot = communityRoot,
            )
        val transport = TransportFactory.create(config)
        val identity = IdentityCrypto(native)
        val crypto = ChatDirectoryCrypto.create(MessageCrypto(Aead(native)), identity)
        return ChatDirectoryService(transport, crypto)
    }
}
