package org.openwebdav.messenger.directory

/**
 * Builds the §10 directory substrate on the **Android** backend (`docs/protocol/webdav-layout.md`).
 * A thin §10-named face over the shared [CommunityDirectoryWiring] (the native binding + transport +
 * `MessageCrypto` it shares with the §11 `ChatDirectoryFactory`); JVM unit tests build [DirectoryService]
 * from a `LazySodiumJava`-backed substrate + a MockWebServer transport directly, so this factory is the
 * app-only entry point — mirroring `crypto/CryptoFactory`, `identity/IdentityFactory`.
 *
 * Construct one [DirectoryFactory] per process and share it (its [CommunityDirectoryWiring] owns the
 * single native binding and reuses the one shared `okhttp3.OkHttpClient`).
 */
class DirectoryFactory {
    private val wiring = CommunityDirectoryWiring()

    /**
     * A [DirectoryService] scoped to [communityRoot] (the §10.1 community-root path supplied in config,
     * NEVER written to disk — SC3/SC4 family). The [baseUrl] + WebDAV credential reach the community's
     * shared space (one credential, Topology A). The **community key** is supplied per call to
     * publish/read (out-of-band / config, §10.2) — it is not held by this factory.
     */
    fun directoryService(
        baseUrl: String,
        username: String,
        appPassword: String,
        communityRoot: String,
    ): DirectoryService {
        val transport = wiring.transport(baseUrl, username, appPassword, communityRoot)
        val crypto = DirectoryCrypto.create(wiring.messageCrypto(), wiring.identityCrypto())
        return DirectoryService(transport, crypto)
    }

    /**
     * The remote-private-chat key provisioning seam (`docs/features/x25519-identity_plan.md`): derive a
     * chat-id-bound DH key from the local identity + a directory-discovered peer + a chat-id and store it
     * under that chat-id, so the existing send/receive path drives a remote private chat **with no secret
     * exchanged over any channel**. Composes this factory's [IdentityCrypto] (the bundled native binding)
     * with the app's Keystore-backed [chatKeyStore] (built via `CryptoFactory.chatKeyStore(context)`).
     */
    fun remoteChatProvisioner(chatKeyStore: org.openwebdav.messenger.keystore.ChatKeyStore): RemoteChatProvisioner =
        RemoteChatProvisioner(wiring.identityCrypto(), chatKeyStore)
}
