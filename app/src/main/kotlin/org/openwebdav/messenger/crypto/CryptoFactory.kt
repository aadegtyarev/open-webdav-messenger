package org.openwebdav.messenger.crypto

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.openwebdav.messenger.keystore.ChatKeyStore

/**
 * Builds the crypto substrate on the **Android** backend — `SodiumAndroid` loads the bundled native
 * libsodium `.so` for the device ABI via JNA (`docs/stack-notes.md` Crypto: a missing ABI =
 * `UnsatisfiedLinkError`, caught by `connectedAndroidTest`). JVM unit tests instead build
 * [LazySodiumCrypto] from `LazySodiumJava` directly, so this factory is the app-only entry point.
 *
 * The single [LazySodiumAndroid] instance is reused (it owns the native binding); construct one
 * [CryptoFactory] per process and share it.
 */
class CryptoFactory {
    private val native: NativeCrypto = LazySodiumCrypto(LazySodiumAndroid(SodiumAndroid()))

    /** AEAD seal/open over the ciphertext-blob (§5.1). */
    fun aead(): Aead = Aead(native)

    /** The three key sources (passphrase / random / known). */
    fun keySources(): KeySources = KeySources(native)

    /** Envelope-integrated seal/open (`header8 ‖ blob`, header as AAD). */
    fun messageCrypto(): MessageCrypto = MessageCrypto(aead())

    /**
     * Device-local, Keystore-wrapped per-chat key store. Shares this factory's single [NativeCrypto]
     * so the on-disk key-file token is derived with the same BLAKE2b primitive as the key sources.
     */
    fun chatKeyStore(context: Context): ChatKeyStore = ChatKeyStore(context, native)
}
