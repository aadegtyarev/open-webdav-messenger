package org.openwebdav.messenger.identity

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.NativeCrypto

/**
 * Builds the identity substrate on the **Android** backend — `SodiumAndroid` loads the bundled native
 * libsodium `.so` for the device ABI via JNA (a missing ABI = `UnsatisfiedLinkError`, caught by
 * `connectedAndroidTest`; `docs/stack-notes.md` Crypto, decision 10). JVM unit tests instead build
 * [IdentityCrypto] from a `LazySodiumJava`-backed [NativeCrypto] directly, so this factory is the
 * app-only entry point — mirroring `crypto/CryptoFactory`.
 *
 * The single [LazySodiumAndroid] instance owns the native binding; construct one [IdentityFactory] per
 * process and share it.
 */
class IdentityFactory {
    private val native: NativeCrypto = LazySodiumCrypto(LazySodiumAndroid(SodiumAndroid()))

    /** The pure-crypto identity operations (generate / agree / seal / sign / fingerprint). */
    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native)

    /** Device-local, Keystore-wrapped identity store (distinct alias from the chat-key store). */
    fun identityStore(context: Context): IdentityStore = IdentityStore(context, identityCrypto())
}
