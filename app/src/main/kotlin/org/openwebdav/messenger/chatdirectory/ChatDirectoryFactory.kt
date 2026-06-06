package org.openwebdav.messenger.chatdirectory

import org.openwebdav.messenger.directory.CommunityDirectoryWiring

/**
 * Builds the §11 chat-directory substrate on the **Android** backend (`docs/protocol/webdav-layout.md`).
 * A thin §11-named face over the shared [CommunityDirectoryWiring] (the native binding + transport +
 * `MessageCrypto` it shares with the §10 `DirectoryFactory`); JVM unit tests build [ChatDirectoryService]
 * from a `LazySodiumJava`-backed substrate + a MockWebServer transport directly, so this factory is the
 * app-only entry point — mirroring `directory/DirectoryFactory`.
 *
 * Construct one [ChatDirectoryFactory] per process and share it (its [CommunityDirectoryWiring] owns the
 * single native binding and reuses the one shared `okhttp3.OkHttpClient`).
 */
class ChatDirectoryFactory {
    private val wiring = CommunityDirectoryWiring()

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
        val transport = wiring.transport(baseUrl, username, appPassword, communityRoot)
        val crypto = ChatDirectoryCrypto.create(wiring.messageCrypto(), wiring.identityCrypto())
        return ChatDirectoryService(transport, crypto)
    }
}
