package org.openwebdav.messenger.identity

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.keystore.KeystoreWrapper
import org.openwebdav.messenger.keystore.UnwrapResult
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Device-local, Keystore-wrapped storage of the user [Identity]'s secret keys (`docs/architecture.md`
 * decision 10 / Security constraints; `docs/stack-notes.md` → Android Keystore).
 *
 * The wrap/unwrap **mechanics** — the Keystore AES/GCM wrapping key, the `iv ‖ ct+tag` on-disk format,
 * the **atomic write** (temp-then-rename; a crash leaves the old file or the new one, never a partial),
 * and the **typed unwrap** (corrupt blob / invalidated Keystore key → [UnwrapResult.Unrecoverable], never
 * an escaping exception) — live in the shared [KeystoreWrapper], under a **distinct alias**
 * ([WRAP_KEY_ALIAS], not the chat-key alias) and a distinct file ([IDENTITY_DIR]/[IDENTITY_FILE]), so an
 * identity store/load does NOT disturb stored chat keys (decision 10: "distinct alias/store").
 *
 * **Policy (distinct from `ChatKeyStore`):** the identity is the user's account. A chat key is
 * re-derivable, so `ChatKeyStore` treats an unrecoverable blob as absent; an identity is **not**
 * re-derivable, so:
 *  - [load] returns [IdentityLoadResult.Unrecoverable] (never silently `None`);
 *  - [loadOrCreate] **surfaces** it as [IdentityUnrecoverableException] and **never regenerates** (silent
 *    regeneration = silent account loss), and never crashes uncaught.
 *
 * **Generate-once is serialised across processes**, not just threads: a future foreground / WorkManager
 * process in a separate `android:process` could also see no file and generate a second identity. A
 * **cross-process file lock** ([generateOnce]) on a dedicated lock file, with a **double-checked load**
 * after acquiring it, guarantees exactly one identity is generated. The in-process [generateOnceMutex] is
 * kept too (a fast path that also bounds same-process contention on the file lock).
 *
 * Android-only — exercised by `connectedAndroidTest` (Keystore is device-backed; cannot run on the JVM).
 */
class IdentityStore(
    private val context: Context,
    private val identityCrypto: IdentityCrypto,
) {
    /**
     * Load the stored identity, or — if none is stored — generate one, store it, and return it.
     *
     * Contract (decision 10 fix 1 + 3):
     *  - generates **only** when [load] reports [IdentityLoadResult.None];
     *  - on [IdentityLoadResult.Unrecoverable], throws [IdentityUnrecoverableException] — never
     *    regenerates (that would be silent account loss), never crashes uncaught;
     *  - generate-once is serialised across **threads and processes** (double-checked under both the
     *    in-process lock and a cross-process [FileLock]).
     */
    suspend fun loadOrCreate(): Identity =
        withContext(Dispatchers.IO) {
            // Fast path: an already-stored identity needs no lock.
            when (val first = load()) {
                is IdentityLoadResult.Loaded -> return@withContext first.identity
                is IdentityLoadResult.Unrecoverable -> throw IdentityUnrecoverableException(first.reason, first.cause)
                is IdentityLoadResult.None -> Unit // fall through to the guarded create
            }
            generateOnceMutex.withLock {
                // In-process re-check: a racing coroutine may have created+stored while we were suspended.
                when (val second = load()) {
                    is IdentityLoadResult.Loaded -> return@withLock second.identity
                    is IdentityLoadResult.Unrecoverable ->
                        throw IdentityUnrecoverableException(second.reason, second.cause)
                    is IdentityLoadResult.None -> Unit
                }
                generateOnce()
            }
        }

    /**
     * Cross-process critical section: hold an exclusive [FileLock] on a dedicated lock file, then
     * **double-check** [load] before generating. A second process that lost the race observes the
     * just-written identity here (load() == Loaded) and returns it instead of overwriting it. The lock
     * file lives in the app-private identity dir alongside the identity blob; it carries no secret data.
     */
    private fun generateOnce(): Identity {
        val lockFile = File(identityDir(), LOCK_FILE)
        // RandomAccessFile.channel.lock() takes an OS advisory lock visible to every process of this app.
        RandomAccessFile(lockFile, "rw").use { raf ->
            val channel: FileChannel = raf.channel
            val lock: FileLock = channel.lock() // blocks until the other process releases
            try {
                when (val underLock = load()) {
                    is IdentityLoadResult.Loaded -> return underLock.identity
                    is IdentityLoadResult.Unrecoverable ->
                        throw IdentityUnrecoverableException(underLock.reason, underLock.cause)
                    is IdentityLoadResult.None -> Unit
                }
                val identity = identityCrypto.generateIdentity()
                store(identity)
                return identity
            } finally {
                lock.release()
            }
        }
    }

    /**
     * The stored identity as a typed [IdentityLoadResult] — [None] if no file, [Loaded] on success,
     * [Unrecoverable] if the file exists but cannot be decrypted/unwrapped. Never throws on a corrupt
     * blob or an invalidated Keystore key: the [KeystoreWrapper] maps those to [UnwrapResult.Unrecoverable].
     */
    fun load(): IdentityLoadResult =
        when (val result = wrapper().unwrap()) {
            is UnwrapResult.None -> IdentityLoadResult.None
            is UnwrapResult.Unrecoverable -> IdentityLoadResult.Unrecoverable(result.reason, result.cause)
            is UnwrapResult.Unwrapped -> {
                val serialized = result.plaintext
                try {
                    val identity = Identity.deserialize(serialized)
                    if (identity == null) {
                        // Right length to decrypt but wrong internal layout — still account-unrecoverable.
                        IdentityLoadResult.Unrecoverable("decrypted identity blob has an invalid layout")
                    } else {
                        IdentityLoadResult.Loaded(identity)
                    }
                } finally {
                    serialized.fill(0)
                }
            }
        }

    /** Wrap [identity]'s serialized bytes under the Keystore key and persist them atomically. */
    fun store(identity: Identity) {
        val serialized = Identity.serialize(identity)
        try {
            wrapper().wrap(serialized)
        } finally {
            serialized.fill(0)
        }
    }

    /** Whether an identity file exists (it may still be unrecoverable — see [load]). */
    fun has(): Boolean = wrapper().exists()

    /** Delete the stored identity (e.g. on an explicit, user-confirmed reset). */
    fun remove() {
        wrapper().delete()
    }

    /** The raw wrapped-on-disk bytes — for tests asserting the secret keys are NOT in plaintext. */
    internal fun rawStoredBlob(): ByteArray? = wrapper().rawBlob()

    /** Test-only: overwrite the identity file with arbitrary bytes (simulate a corrupt/partial write). */
    internal fun writeRawBlobForTest(bytes: ByteArray) {
        File(identityDir(), IDENTITY_FILE).writeBytes(bytes)
    }

    private fun wrapper(): KeystoreWrapper = KeystoreWrapper(WRAP_KEY_ALIAS, File(identityDir(), IDENTITY_FILE))

    private fun identityDir(): File = File(context.filesDir, IDENTITY_DIR).apply { mkdirs() }

    companion object {
        /** Distinct from `ChatKeyStore`'s `owdm.chatkey.wrap.v1` — identity wrap key, separate alias. */
        private const val WRAP_KEY_ALIAS = "owdm.identity.wrap.v1"

        /** Distinct directory from `ChatKeyStore`'s `chatkeys` so the two stores never collide. */
        private const val IDENTITY_DIR = "identity"
        private const val IDENTITY_FILE = "identity.bin"

        /** Dedicated cross-process lock file for the generate-once critical section. Holds no secret. */
        private const val LOCK_FILE = "identity.lock"

        /** In-process fast-path lock for the generate-once guard ([loadOrCreate]). */
        private val generateOnceMutex = Mutex()
    }
}
