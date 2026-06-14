package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.export.ExportableConnectionConfigStore
import org.openwebdav.messenger.transport.ConnectionConfig
import java.io.File

/**
 * Device-local, Keystore-wrapped storage of the WebDAV [ConnectionConfig]
 * (base URL, username, app-password, chat-root).
 *
 * The stored blob is Keystore-wrapped so the app-password is never in plaintext on disk.
 * The wrap/unwrap mechanics live in the shared [KeystoreWrapper] under a distinct alias
 * ([WRAP_KEY_ALIAS]), separate from the chat-key, identity, and history stores.
 *
 * Policy: the connection config can be re-entered by the user, so an unrecoverable blob
 * is treated as absent (same re-derivable policy as [ChatKeyStore]).
 *
 * Android-only — exercised by connectedAndroidTest (Keystore is device-backed).
 */
class ConnectionConfigStore(context: Context) : ExportableConnectionConfigStore {
    /**
     * Wrap and persist [config]. The app-password is serialized as part of the config and
     * is never in plaintext on disk. Overwrites any existing stored config atomically.
     */
    override fun store(config: ConnectionConfig) {
        val serialized = serialize(config)
        try {
            wrapper.wrap(serialized)
        } finally {
            serialized.fill(0)
        }
    }

    /** Load and unwrap the stored config, or null if none stored or blob is unrecoverable. */
    override fun load(): ConnectionConfig? {
        return when (val result = wrapper.unwrap()) {
            is UnwrapResult.None -> null
            is UnwrapResult.Unrecoverable -> null
            is UnwrapResult.Unwrapped -> {
                val raw = result.plaintext
                try {
                    deserialize(raw)
                } finally {
                    raw.fill(0)
                }
            }
        }
    }

    /** Whether a wrapped config blob exists. */
    fun has(): Boolean = wrapper.exists()

    /** Delete the stored config. */
    fun remove() {
        wrapper.delete()
    }

    /** The raw wrapped-on-disk bytes — for tests. */
    internal fun rawStoredBlob(): ByteArray? = wrapper.rawBlob()

    private val wrapper =
        KeystoreWrapper(
            WRAP_KEY_ALIAS,
            File(context.filesDir, CONFIG_DIR).let { dir ->
                dir.mkdirs()
                File(dir, CONFIG_FILE)
            },
        )

    private companion object {
        /** Distinct from chat-key, identity, history, and community-key aliases. */
        private const val WRAP_KEY_ALIAS = "owdm.connection.wrap.v1"

        private const val CONFIG_DIR = "connection"
        private const val CONFIG_FILE = "connection_config.bin"

        // Simple fixed-field serialization: each field on its own line, length-prefixed.
        // Field separator is US (0x1F) — not valid in URLs or credentials.

        private fun serialize(config: ConnectionConfig): ByteArray {
            val sb = StringBuilder()
            sb.append(config.baseUrl).append('\u001F')
            sb.append(config.username).append('\u001F')
            sb.append(config.appPassword).append('\u001F')
            sb.append(config.chatRoot)
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        private fun deserialize(bytes: ByteArray): ConnectionConfig? {
            val s = String(bytes, Charsets.UTF_8)
            val parts = s.split('\u001F')
            if (parts.size != 4) return null
            return ConnectionConfig(
                baseUrl = parts[0],
                username = parts[1],
                appPassword = parts[2],
                chatRoot = parts[3],
            )
        }
    }
}
