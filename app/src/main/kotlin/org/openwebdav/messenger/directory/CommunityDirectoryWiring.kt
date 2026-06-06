package org.openwebdav.messenger.directory

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * Shared **Android-backend** wiring for a community-scoped directory service
 * (`docs/protocol/webdav-layout.md` §10 / §11). The §10 `DirectoryFactory` and the §11
 * `ChatDirectoryFactory` build the SAME native + transport + `MessageCrypto` substrate around a single
 * [LazySodiumAndroid] binding (a missing ABI = `UnsatisfiedLinkError`, caught by `connectedAndroidTest`;
 * `docs/stack-notes.md` Crypto) and differ ONLY in which typed `*Crypto`/`*Service` they wire on top.
 * This is that one shared wiring; the two public factories hold one instance each and keep their own
 * §-named public `*Service(...)` method.
 *
 * The single [LazySodiumAndroid] instance owns the native binding, and the transport reuses the one
 * shared `okhttp3.OkHttpClient` (`TransportFactory.sharedClient`). Construct one per process and share it.
 */
internal class CommunityDirectoryWiring {
    private val native: NativeCrypto = LazySodiumCrypto(LazySodiumAndroid(SodiumAndroid()))

    /** The shared `MessageCrypto` (§5 envelope + AEAD) over the bundled native binding. */
    fun messageCrypto(): MessageCrypto = MessageCrypto(Aead(native))

    /** A fresh `IdentityCrypto` (Ed25519 sign/verify) over the bundled native binding. */
    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native)

    /**
     * A community-root-scoped [WebDavTransport]: the §10.1/§11.1 community-root takes the chat-root slot
     * of the connection config — the transport joins it as the URL path prefix, so the directory's
     * PROPFIND/GET/PUT/MKCOL all sit under `<base>/<community-root>/<collection>/`. The community-root is
     * NEVER written to disk (SC3/SC4 family); the credential reaches the community's shared space (one
     * credential, Topology A).
     */
    fun transport(
        baseUrl: String,
        username: String,
        appPassword: String,
        communityRoot: String,
    ): WebDavTransport =
        TransportFactory.create(
            ConnectionConfig(
                baseUrl = baseUrl,
                username = username,
                appPassword = appPassword,
                chatRoot = communityRoot,
            ),
        )
}
