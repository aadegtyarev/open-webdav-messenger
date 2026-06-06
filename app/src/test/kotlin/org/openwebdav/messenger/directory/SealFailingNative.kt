package org.openwebdav.messenger.directory

import org.openwebdav.messenger.crypto.BoxKeyPair
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.crypto.SignKeyPair

/**
 * A [NativeCrypto] that simulates a runtime native AEAD **seal** failure: every libsodium call is
 * delegated to a real [delegate] backend EXCEPT [aeadEncrypt], which throws the same
 * `IllegalStateException` a spurious `crypto_aead_..._encrypt` failure surfaces (the `check()` in
 * `LazySodiumCrypto.aeadEncrypt`). The decrypt/sign/verify paths stay real, so a read after a failed
 * publish still works — exactly the C8 interaction scenario. Shared by the directory + chat-directory
 * seal-failure tests.
 */
internal class SealFailingNative(private val delegate: NativeCrypto) : NativeCrypto {
    override fun aeadEncrypt(
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray = throw IllegalStateException("AEAD encrypt failed")

    override fun aeadDecrypt(
        ciphertextWithTag: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray? = delegate.aeadDecrypt(ciphertextWithTag, aad, nonce, key)

    override fun argon2id(
        passphrase: ByteArray,
        salt: ByteArray,
        outLen: Int,
        opsLimit: Long,
        memLimitBytes: Int,
    ): ByteArray = delegate.argon2id(passphrase, salt, outLen, opsLimit, memLimitBytes)

    override fun genericHash(
        input: ByteArray,
        outLen: Int,
    ): ByteArray = delegate.genericHash(input, outLen)

    override fun randomBytes(count: Int): ByteArray = delegate.randomBytes(count)

    override fun boxKeypair(): BoxKeyPair = delegate.boxKeypair()

    override fun signKeypair(): SignKeyPair = delegate.signKeypair()

    override fun boxBeforeNm(
        peerBoxPk: ByteArray,
        myBoxSk: ByteArray,
    ): ByteArray = delegate.boxBeforeNm(peerBoxPk, myBoxSk)

    override fun boxSeal(
        plaintext: ByteArray,
        recipientBoxPk: ByteArray,
    ): ByteArray = delegate.boxSeal(plaintext, recipientBoxPk)

    override fun boxSealOpen(
        sealed: ByteArray,
        myBoxPk: ByteArray,
        myBoxSk: ByteArray,
    ): ByteArray? = delegate.boxSealOpen(sealed, myBoxPk, myBoxSk)

    override fun signDetached(
        message: ByteArray,
        signSk: ByteArray,
    ): ByteArray = delegate.signDetached(message, signSk)

    override fun signVerifyDetached(
        signature: ByteArray,
        message: ByteArray,
        signPk: ByteArray,
    ): Boolean = delegate.signVerifyDetached(signature, message, signPk)

    override fun keyedHash(
        input: ByteArray,
        key: ByteArray,
        outLen: Int,
    ): ByteArray = delegate.keyedHash(input, key, outLen)
}
