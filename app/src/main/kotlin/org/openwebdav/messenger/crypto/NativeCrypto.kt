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

    // ---- Public-key primitives (X25519 identity substrate, decision 10) -----------------------
    // The same lazysodium base type backs these as the AEAD calls above, so the identity logic runs
    // on lazysodium-java (JVM tests) and lazysodium-android (app) unchanged. All operate on raw
    // bytes via the `Box.Native` / `Sign.Native` / `GenericHash.Native` mode — NOT the String-based
    // `*Easy` Lazy methods, which would corrupt binary key/ciphertext bytes through charset
    // conversion. (The stack-notes `cryptoBoxSealEasy` naming gotcha is about that Lazy API; the
    // native-mode names below are `cryptoBoxSeal` / `cryptoBoxSealOpen`.)

    /**
     * Generate an X25519 box keypair (`crypto_box_keypair`). Returns `pk(32) ‖ sk(32)` as a
     * [BoxKeyPair]. Source: https://doc.libsodium.org/public-key_cryptography/authenticated_encryption
     */
    fun boxKeypair(): BoxKeyPair

    /**
     * Generate an Ed25519 signing keypair (`crypto_sign_keypair`). Returns `pk(32) ‖ sk(64)` as a
     * [SignKeyPair] — the 64-byte sk embeds the seed and the public key.
     * Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
     */
    fun signKeypair(): SignKeyPair

    /**
     * X25519 precomputed shared secret (`crypto_box_beforenm`) for [peerBoxPk] (32) and [myBoxSk]
     * (32). Returns the 32-byte DH output. This is a DH output, NOT an AEAD key — callers MUST run
     * it through a KDF before use (see [genericHash] / `identity/Kdf`). Symmetric: beforenm(B.pk, A.sk)
     * == beforenm(A.pk, B.sk). Source: https://doc.libsodium.org/public-key_cryptography/authenticated_encryption
     */
    fun boxBeforeNm(
        peerBoxPk: ByteArray,
        myBoxSk: ByteArray,
    ): ByteArray

    /**
     * Anonymous sealed box (`crypto_box_seal`) — encrypt [plaintext] to [recipientBoxPk] (32) with an
     * ephemeral internal sender keypair. Overhead is `crypto_box_SEALBYTES` = 48. Sender-unauthenticated
     * by design. Source: https://doc.libsodium.org/public-key_cryptography/sealed_boxes
     */
    fun boxSeal(
        plaintext: ByteArray,
        recipientBoxPk: ByteArray,
    ): ByteArray

    /**
     * Open a sealed box (`crypto_box_seal_open`) with the recipient's keypair. Returns the plaintext,
     * or `null` if [sealed] does not open under ([myBoxPk], [myBoxSk]) (wrong recipient / tampered) —
     * the typed-rejection path, never an exception.
     */
    fun boxSealOpen(
        sealed: ByteArray,
        myBoxPk: ByteArray,
        myBoxSk: ByteArray,
    ): ByteArray?

    /**
     * Ed25519 detached signature (`crypto_sign_detached`) of [message] under [signSk] (64). Returns the
     * 64-byte signature. Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
     */
    fun signDetached(
        message: ByteArray,
        signSk: ByteArray,
    ): ByteArray

    /**
     * Verify an Ed25519 detached [signature] (64) over [message] against [signPk] (32)
     * (`crypto_sign_verify_detached`). Returns `true` only on libsodium success (0); `false` on any
     * failure (the `-1` hard reject) — never best-effort.
     * Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
     */
    fun signVerifyDetached(
        signature: ByteArray,
        message: ByteArray,
        signPk: ByteArray,
    ): Boolean

    /**
     * Keyed BLAKE2b (`crypto_generichash` with a key) — [outLen] bytes of `BLAKE2b(input, key)`. Used by
     * the DH→KDF→ChatKey derivation so the shared secret is keyed/domain-separated, never fed raw to the
     * AEAD. Source: https://doc.libsodium.org/hashing/generic_hashing
     */
    fun keyedHash(
        input: ByteArray,
        key: ByteArray,
        outLen: Int,
    ): ByteArray
}

/** An X25519 box keypair: [publicKey] (32) and [secretKey] (32). Raw bytes — in-memory only. */
class BoxKeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

/** An Ed25519 signing keypair: [publicKey] (32) and [secretKey] (64). Raw bytes — in-memory only. */
class SignKeyPair(val publicKey: ByteArray, val secretKey: ByteArray)
