package org.openwebdav.messenger.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.Sign
import com.sun.jna.NativeLong

/**
 * [NativeCrypto] backed by lazysodium's shared [LazySodium] base type. The **same** adapter serves
 * both backends: tests construct it from `LazySodiumJava` (system libsodium), the app from
 * `LazySodiumAndroid` (bundled `.so`). Only the native-mode calls this feature needs are wired.
 *
 * All calls go through libsodium's audited primitives — no hand-rolled crypto
 * (`docs/architecture.md` → "No hand-rolled crypto", decision 1 = libsodium-only).
 */
class LazySodiumCrypto(private val sodium: LazySodium) : NativeCrypto {
    override fun aeadEncrypt(
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray {
        require(nonce.size == AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES) { "nonce must be 24 bytes" }
        require(key.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) { "key must be 32 bytes" }
        val out = ByteArray(plaintext.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val outLen = LongArray(1)
        val ok =
            sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                out,
                outLen,
                plaintext,
                plaintext.size.toLong(),
                aad,
                aad.size.toLong(),
                null,
                nonce,
                key,
            )
        check(ok) { "AEAD encrypt failed" }
        return if (outLen[0].toInt() == out.size) out else out.copyOf(outLen[0].toInt())
    }

    override fun aeadDecrypt(
        ciphertextWithTag: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray? {
        if (nonce.size != AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES) return null
        if (key.size != AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) return null
        if (ciphertextWithTag.size < AEAD.XCHACHA20POLY1305_IETF_ABYTES) return null
        val out = ByteArray(ciphertextWithTag.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val outLen = LongArray(1)
        val ok =
            sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                out,
                outLen,
                null,
                ciphertextWithTag,
                ciphertextWithTag.size.toLong(),
                aad,
                aad.size.toLong(),
                nonce,
                key,
            )
        // libsodium returns false (non-zero) on any verification failure — the typed-rejection path.
        if (!ok) return null
        return if (outLen[0].toInt() == out.size) out else out.copyOf(outLen[0].toInt())
    }

    override fun argon2id(
        passphrase: ByteArray,
        salt: ByteArray,
        outLen: Int,
        opsLimit: Long,
        memLimitBytes: Int,
    ): ByteArray {
        require(salt.size == PwHash.ARGON2ID_SALTBYTES) { "Argon2id salt must be ${PwHash.ARGON2ID_SALTBYTES} bytes" }
        val out = ByteArray(outLen)
        val ok =
            sodium.cryptoPwHash(
                out,
                outLen,
                passphrase,
                passphrase.size,
                salt,
                opsLimit,
                NativeLong(memLimitBytes.toLong()),
                PwHash.Alg.PWHASH_ALG_ARGON2ID13,
            )
        check(ok) { "Argon2id derivation failed" }
        return out
    }

    override fun genericHash(
        input: ByteArray,
        outLen: Int,
    ): ByteArray {
        val out = ByteArray(outLen)
        val ok = sodium.cryptoGenericHash(out, outLen, input, input.size.toLong())
        check(ok) { "generic hash failed" }
        return out
    }

    override fun randomBytes(count: Int): ByteArray = sodium.randomBytesBuf(count)

    // ---- Public-key primitives (decision 10) — native-mode byte[] calls, like the AEAD above. ----

    override fun boxKeypair(): BoxKeyPair {
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        check(sodium.cryptoBoxKeypair(pk, sk)) { "crypto_box_keypair failed" }
        return BoxKeyPair(pk, sk)
    }

    override fun signKeypair(): SignKeyPair {
        val pk = ByteArray(Sign.PUBLICKEYBYTES)
        val sk = ByteArray(Sign.SECRETKEYBYTES)
        check(sodium.cryptoSignKeypair(pk, sk)) { "crypto_sign_keypair failed" }
        return SignKeyPair(pk, sk)
    }

    override fun boxBeforeNm(
        peerBoxPk: ByteArray,
        myBoxSk: ByteArray,
    ): ByteArray {
        require(peerBoxPk.size == Box.PUBLICKEYBYTES) { "box public key must be ${Box.PUBLICKEYBYTES} bytes" }
        require(myBoxSk.size == Box.SECRETKEYBYTES) { "box secret key must be ${Box.SECRETKEYBYTES} bytes" }
        val shared = ByteArray(Box.BEFORENMBYTES)
        check(sodium.cryptoBoxBeforeNm(shared, peerBoxPk, myBoxSk)) { "crypto_box_beforenm failed" }
        return shared
    }

    override fun boxSeal(
        plaintext: ByteArray,
        recipientBoxPk: ByteArray,
    ): ByteArray {
        require(recipientBoxPk.size == Box.PUBLICKEYBYTES) { "box public key must be ${Box.PUBLICKEYBYTES} bytes" }
        val sealed = ByteArray(plaintext.size + Box.SEALBYTES)
        check(sodium.cryptoBoxSeal(sealed, plaintext, plaintext.size.toLong(), recipientBoxPk)) {
            "crypto_box_seal failed"
        }
        return sealed
    }

    override fun boxSealOpen(
        sealed: ByteArray,
        myBoxPk: ByteArray,
        myBoxSk: ByteArray,
    ): ByteArray? {
        if (myBoxPk.size != Box.PUBLICKEYBYTES || myBoxSk.size != Box.SECRETKEYBYTES) return null
        if (sealed.size < Box.SEALBYTES) return null
        val plaintext = ByteArray(sealed.size - Box.SEALBYTES)
        // Returns false on any failure (wrong recipient, tampered) — the typed-rejection path.
        if (!sodium.cryptoBoxSealOpen(plaintext, sealed, sealed.size.toLong(), myBoxPk, myBoxSk)) return null
        return plaintext
    }

    override fun signDetached(
        message: ByteArray,
        signSk: ByteArray,
    ): ByteArray {
        require(signSk.size == Sign.SECRETKEYBYTES) { "sign secret key must be ${Sign.SECRETKEYBYTES} bytes" }
        val sig = ByteArray(Sign.BYTES)
        check(sodium.cryptoSignDetached(sig, message, message.size.toLong(), signSk)) {
            "crypto_sign_detached failed"
        }
        return sig
    }

    override fun signVerifyDetached(
        signature: ByteArray,
        message: ByteArray,
        signPk: ByteArray,
    ): Boolean {
        if (signature.size != Sign.BYTES || signPk.size != Sign.PUBLICKEYBYTES) return false
        // libsodium returns -1 on failure → lazysodium returns false; hard reject, never best-effort.
        return sodium.cryptoSignVerifyDetached(signature, message, message.size, signPk)
    }

    override fun keyedHash(
        input: ByteArray,
        key: ByteArray,
        outLen: Int,
    ): ByteArray {
        val out = ByteArray(outLen)
        check(sodium.cryptoGenericHash(out, outLen, input, input.size.toLong(), key, key.size)) {
            "keyed generic hash failed"
        }
        return out
    }
}
