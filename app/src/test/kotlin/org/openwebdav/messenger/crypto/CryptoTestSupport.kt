package org.openwebdav.messenger.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

/**
 * Builds a real, libsodium-backed [NativeCrypto] for JVM unit tests. lazysodium-java loads the
 * host's system libsodium (the build host has `libsodium.so.23`), so AEAD + Argon2id run for real
 * in `./gradlew test` — the same code the app runs on lazysodium-android, behind [NativeCrypto].
 */
internal object CryptoTestSupport {
    private val sodium: LazySodiumJava by lazy { LazySodiumJava(SodiumJava()) }

    fun native(): NativeCrypto = LazySodiumCrypto(sodium)

    fun aead(): Aead = Aead(native())

    /** A deterministic non-random key for tests that only need *a* valid 32-byte key. */
    fun fixedKey(seed: Byte = 7): ChatKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { seed })
}
