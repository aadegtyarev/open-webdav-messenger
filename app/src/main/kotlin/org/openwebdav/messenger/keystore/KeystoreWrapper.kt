package org.openwebdav.messenger.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The shared Android-Keystore wrap/unwrap **mechanics** for the project's two secret-blob stores —
 * `ChatKeyStore` (per-chat keys) and `identity/IdentityStore` (the user identity). Both previously
 * duplicated the identical discipline; this single seam owns it (`docs/architecture.md` decision 9/10,
 * Security constraints; `docs/stack-notes.md` → Android Keystore).
 *
 * Mechanics owned here (parameterised by [alias] + [file]):
 *  - a **non-exportable AES/GCM-256 wrapping key generated in the Android Keystore** under [alias]
 *    (`androidx.security:security-crypto` is deprecated — not used; stack-notes);
 *  - the on-disk format `iv(12) ‖ ciphertext+tag` — only the wrapped blob ever touches disk, never the
 *    raw key/passphrase/identity (Security constraints), never logged;
 *  - temp-file writes in the same directory with fsync before replacement; [wrapStrictAtomic] requires
 *    atomic `REPLACE_EXISTING` and preserves the old target on failure, while legacy [wrap] retains its
 *    historical fallback for stores that have not adopted strict replacement;
 *  - a **typed unwrap** ([unwrap]) — a corrupt/partial blob, or a Keystore wrapping key invalidated by
 *    an OS/lockscreen change (`AEADBadTagException` / `KeyPermanentlyInvalidatedException` / any GCM
 *    failure) is mapped to [UnwrapResult.Unrecoverable], never an exception escaping to crash a caller.
 *
 * **Policy stays with each store.** The wrapper decides nothing about what an [UnwrapResult.Unrecoverable]
 * means: a lost chat key is re-derivable (passphrase) / re-agreeable, so `ChatKeyStore` may treat it as
 * absent; a lost identity is account loss, so `IdentityStore` MUST surface it and never regenerate. Same
 * mechanics, different policy.
 *
 * Android-only — Keystore is device-backed (TEE/StrongBox), so wrap/unwrap is exercised by
 * `connectedAndroidTest`, not the JVM (stack-notes → Android Keystore: instrumented-only).
 */
class KeystoreWrapper internal constructor(
    private val alias: String,
    private val file: File,
    private val atomicReplace: (File, File) -> Unit,
) {
    constructor(alias: String, file: File) : this(alias, file, { source, target -> StrictFileOperations.atomicReplace(source, target) })

    /**
     * Encrypt [plaintext] under the Keystore wrapping key and persist the `iv ‖ ct+tag` blob using the
     * legacy atomic-write fallback. Cache storage uses [wrapStrictAtomic] to reject a failed atomic replace
     * without deleting the previous target. The [plaintext] buffer remains caller-owned.
     *
     * Throws [java.io.IOException] on any Keystore failure — [KeyStoreException], `ProviderException`,
     * `InvalidKeyException` etc. can surface from [wrappingKey] or [Cipher.init] under TEE pressure;
     * wrapping them as [IOException] gives callers a typed storage-layer signal (matches [unwrap] pattern).
     */
    @Suppress("TooGenericExceptionCaught") // varied Keystore/TEE runtime exceptions; all map to IOException
    fun wrap(plaintext: ByteArray) = wrap(plaintext, strictAtomic = false)

    /** Atomically replace [file]; failure preserves the existing target and removes only the temp file. */
    fun wrapStrictAtomic(plaintext: ByteArray) = wrap(plaintext, strictAtomic = true)

    @Suppress("TooGenericExceptionCaught") // varied Keystore/TEE runtime exceptions map to IOException
    private fun wrap(
        plaintext: ByteArray,
        strictAtomic: Boolean,
    ) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val iv = cipher.iv
            val wrapped = cipher.doFinal(plaintext)
            val blob = ByteArray(iv.size + wrapped.size)
            iv.copyInto(blob, 0)
            wrapped.copyInto(blob, iv.size)
            writeAtomically(blob, strictAtomic)
        } catch (e: Exception) {
            throw IOException("Keystore wrap failed", e)
        }
    }

    /**
     * Read and decrypt [file]:
     *  - [UnwrapResult.None] if [file] does not exist (or is too short to hold an IV) — no blob yet.
     *  - [UnwrapResult.Unwrapped] with the plaintext on success (caller owns zeroizing it).
     *  - [UnwrapResult.Unrecoverable] if the file EXISTS but cannot be decrypted — corrupt/partial blob,
     *    or the Keystore wrapping key was invalidated. The GCM/Keystore exception is caught here and
     *    mapped, never allowed to escape.
     */
    @Suppress("TooGenericExceptionCaught") // GCM/Keystore failures surface as varied runtime exceptions; all map to Unrecoverable.
    fun unwrap(): UnwrapResult {
        if (!file.exists()) return UnwrapResult.None
        val blob = file.readBytes()
        // A truncated/zero-length file (e.g. an interrupted pre-atomic-write blob from an older build)
        // is too short to hold even the IV — treat as Unrecoverable, not None: the file exists, so a
        // policy that must-not-regenerate (IdentityStore) needs to see it as a failure, not "no blob".
        if (blob.size <= IV_BYTES) {
            return UnwrapResult.Unrecoverable("stored blob too short (${blob.size} bytes) to hold iv+ciphertext")
        }
        return try {
            val iv = blob.copyOfRange(0, IV_BYTES)
            val wrapped = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            UnwrapResult.Unwrapped(cipher.doFinal(wrapped))
        } catch (e: Exception) {
            // AEADBadTagException (corrupt/tampered blob, or wrong/invalidated key),
            // KeyPermanentlyInvalidatedException (lockscreen/biometric change invalidated the Keystore
            // key), IllegalBlockSize/BadPadding, or any KeyStoreException — all are "the file is there
            // but we cannot get the plaintext back". Map to Unrecoverable; never crash the caller.
            UnwrapResult.Unrecoverable(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), e)
        }
    }

    /** Whether a wrapped blob file exists. */
    fun exists(): Boolean = file.exists()

    /** Delete the wrapped blob file and report failure to replacement/rollback callers. */
    fun delete() = StrictFileOperations.delete(file)

    /** Destroy this wrapper's encryption key so undeletable stale ciphertext remains unreadable. */
    fun destroyWrappingKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (failure: Exception) {
            throw IOException("Keystore key removal failed", failure)
        }
    }

    /** The raw wrapped-on-disk bytes, or `null` if absent — for tests asserting no plaintext leaks. */
    fun rawBlob(): ByteArray? = file.takeIf { it.exists() }?.readBytes()

    private fun writeAtomically(
        blob: ByteArray,
        strictAtomic: Boolean,
    ) {
        val dir = file.parentFile ?: error("wrapped-blob file has no parent dir: $file")
        dir.mkdirs()
        val temp = File.createTempFile(file.name + ".", TEMP_SUFFIX, dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(blob)
                out.flush()
                // fsync the bytes to stable storage before the rename, so the rename cannot expose a
                // file whose contents are still only in the page cache after a power loss.
                out.fd.sync()
            }
            try {
                atomicReplace(temp, file)
            } catch (failure: Exception) {
                if (strictAtomic) throw failure
                // Legacy callers retain their historical fallback; cache writes never take this path.
                file.delete()
                if (!temp.renameTo(file)) throw failure
            }
        } finally {
            if (temp.exists() && !temp.delete()) throw IOException("Could not remove temporary wrapped blob")
        }
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(WRAP_KEY_BITS)
                .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WRAP_KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * The typed result of [KeystoreWrapper.unwrap] — distinguishes "no blob yet" from "blob exists but
 * cannot be recovered", so a store can apply its policy (regenerate vs surface) without an exception
 * ever escaping the wrap layer (`docs/architecture.md` decision 10 fix 3).
 */
sealed interface UnwrapResult {
    /** No blob file exists yet — the only case in which a generate-once store may generate. */
    data object None : UnwrapResult

    /** Success — [plaintext] is the decrypted bytes; the caller owns zeroizing it. */
    class Unwrapped(val plaintext: ByteArray) : UnwrapResult

    /**
     * The blob file EXISTS but cannot be decrypted/unwrapped — corrupt/partial, or the Keystore key was
     * invalidated. [reason] is a non-secret diagnostic; [cause] is the underlying exception if any. A
     * store that must not silently regenerate (IdentityStore) MUST surface this.
     */
    class Unrecoverable(val reason: String, val cause: Throwable? = null) : UnwrapResult
}
