package org.openwebdav.messenger.crypto

/**
 * The libsodium primitives the crypto substrate needs, abstracted behind an interface so the
 * **same** [Aead] / [KeySources] logic runs against two backends:
 *  - JVM unit tests use lazysodium-**java** (the host's system libsodium) — `./gradlew test`.
 *  - The app uses lazysodium-**android** (the bundled native `.so` per ABI) — on device.
 *
 * Only the four operations this feature uses are exposed; the wider libsodium surface stays out
 * (`docs/architecture.md` decision 1 = libsodium-only; decision 9 = AEAD + Argon2id + KDF + CSPRNG).
 * Every method operates on raw bytes — no key material is logged or persisted here.
 */
interface NativeCrypto {
    /**
     * XChaCha20-Poly1305 IETF AEAD combined-mode encrypt (`crypto_aead_xchacha20poly1305_ietf_encrypt`).
     * Returns `ciphertext ‖ tag(16)` (`docs/protocol/webdav-layout.md` §5.1). [aad] is authenticated
     * but not encrypted (the 8-byte envelope header). [nonce] is 24 bytes, [key] is 32 bytes.
     */
    fun aeadEncrypt(
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray

    /**
     * XChaCha20-Poly1305 IETF AEAD combined-mode decrypt (`crypto_aead_xchacha20poly1305_ietf_decrypt`).
     * Returns the original plaintext, or `null` on any authentication failure (wrong key, tampered
     * AAD/ciphertext/tag) — the typed-rejection path (§5.1). [ciphertextWithTag] is `ciphertext ‖ tag(16)`.
     */
    fun aeadDecrypt(
        ciphertextWithTag: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray?

    /**
     * Argon2id memory-hard KDF (`crypto_pwhash`, alg = Argon2id) producing [outLen] bytes from
     * [passphrase] + [salt] at the given ops/mem limits. Intentionally slow — callers run it off the
     * UI thread (`docs/stack-notes.md` Kotlin/Crypto). Source: https://doc.libsodium.org/password_hashing/default_phf
     */
    fun argon2id(
        passphrase: ByteArray,
        salt: ByteArray,
        outLen: Int,
        opsLimit: Long,
        memLimitBytes: Int,
    ): ByteArray

    /**
     * BLAKE2b generic hash (`crypto_generichash`) of [input] producing [outLen] bytes. Used to derive
     * the deterministic Argon2id salt from a chat-id and the known-chat key from
     * `in-app constant ‖ chat-id`. Fast — NOT used for passphrase stretching (that is [argon2id]).
     */
    fun genericHash(
        input: ByteArray,
        outLen: Int,
    ): ByteArray

    /** CSPRNG bytes (`randombytes_buf`) — used for the random key source and the per-seal nonce. */
    fun randomBytes(count: Int): ByteArray
}
