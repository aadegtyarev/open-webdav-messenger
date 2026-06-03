package org.openwebdav.messenger.identity

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.NativeCrypto

/**
 * Builds a real, libsodium-backed [NativeCrypto] / [IdentityCrypto] for JVM unit tests. lazysodium-java
 * loads the host's system libsodium, so the public-key paths (crypto_box / crypto_sign / sealed-box /
 * generichash) run for real in `./gradlew test` — the same code the app runs on lazysodium-android,
 * behind [NativeCrypto].
 */
internal object IdentityTestSupport {
    private val sodium: LazySodiumJava by lazy { LazySodiumJava(SodiumJava()) }

    fun native(): NativeCrypto = LazySodiumCrypto(sodium)

    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native())

    /**
     * The ed25519→curve25519 public-key conversion (the path the substrate deliberately does NOT use,
     * decision 10). Test-only — lets the stack-spec test prove the box key is independently generated,
     * i.e. NOT equal to convert(signPub). Source: https://doc.libsodium.org/advanced/ed25519-curve25519
     */
    fun convertSignPubToBoxPub(ed25519Pub: ByteArray): ByteArray {
        val curve = ByteArray(Box.PUBLICKEYBYTES)
        check(ed25519Pub.size == Sign.PUBLICKEYBYTES)
        check(sodium.convertPublicKeyEd25519ToCurve25519(curve, ed25519Pub)) { "conversion failed" }
        return curve
    }
}
