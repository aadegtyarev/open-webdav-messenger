package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.export.ExportableConnectionConfigStore
import org.openwebdav.messenger.transport.ConnectionConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Device-local, Keystore-wrapped storage of the WebDAV [ConnectionConfig] (URL + username + app-password
 * + chat-root) plus the joined community chat-id and community name (`ui-chat-surface` plan → Contracts:
 * "A secure on-device store for the WebDAV connection config"; arch note Choice 3).
 *
 * Built exactly like [ChatKeyStore] / `IdentityStore`: the serialized config bytes are wrapped via the
 * shared [KeystoreWrapper] under a **distinct alias** ([WRAP_KEY_ALIAS]) and a distinct file, so it never
 * disturbs the chat-key / identity blobs. The whole blob — including the secret **app-password** — is
 * device-local, app-private, **never written to the WebDAV disk and never logged** (SC4; `androidx.security`
 * EncryptedSharedPreferences is deprecated and not used). The random chat key continues to live in the
 * existing Keystore-wrapped [ChatKeyStore], keyed by chat-id; this store holds only the config + the
 * joined-chat marker.
 *
 * Android-only — exercised by `connectedAndroidTest` (Keystore is device-backed; cannot run on the JVM).
 * The serialize/deserialize round-trip is JVM-testable in isolation (no Keystore) where needed.
 */
internal class ConnectionConfigStore(
    private val context: Context,
) : ExportableConnectionConfigStore {
    /** Persist [config] + the [chatId] / [communityName] of the joined community chat (atomic, wrapped). */
    fun save(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        communityId: String = DEFAULT_COMMUNITY_ID,
    ) {
        val serialized = serialize(config, chatId, communityName)
        try {
            wrapper(communityId).wrap(serialized)
        } finally {
            serialized.fill(0)
        }
    }

    /** Load the stored config for [communityId], or `null`. */
    fun loadStored(communityId: String): StoredConnection? =
        when (val result = wrapper(communityId).unwrap()) {
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

    /** Legacy: load the first/default stored config. */
    fun loadStored(): StoredConnection? = loadStored(DEFAULT_COMMUNITY_ID)

    /** ExportableConnectionConfigStore: load just the [ConnectionConfig], discarding chatId/communityName. */
    override fun load(): ConnectionConfig? = loadStored()?.config

    /** ExportableConnectionConfigStore: store a bare config (restore path — no chatId/communityName yet). */
    override fun store(config: ConnectionConfig) {
        save(config, chatId = "", communityName = "")
    }

    /** Whether a wrapped config blob exists. */
    fun has(): Boolean = has(DEFAULT_COMMUNITY_ID)

    fun has(communityId: String): Boolean = wrapper(communityId).exists()

    /** Delete the stored config. */
    fun clear() = clear(DEFAULT_COMMUNITY_ID)

    fun clear(communityId: String) = wrapper(communityId).delete()

    private fun wrapper(communityId: String): KeystoreWrapper =
        KeystoreWrapper("${WRAP_KEY_ALIAS}.$communityId", File(configDir(), "$CONFIG_FILE-$communityId"))

    private fun configDir(): File = File(context.filesDir, CONFIG_DIR).apply { mkdirs() }

    companion object {
        const val DEFAULT_COMMUNITY_ID = "default"

        /** Distinct from the chat-key (`owdm.chatkey.wrap.v1`) and identity (`owdm.identity.wrap.v1`) aliases. */
        private const val WRAP_KEY_ALIAS = "owdm.connconfig.wrap.v1"
        private const val CONFIG_DIR = "connconfig"
        private const val CONFIG_FILE = "config.bin"

        /** A version byte at the head of the blob so a future field change is reject-don't-guess. */
        private const val BLOB_VERSION = 1

        /** Serialize the config + joined-chat marker to a length-prefixed byte layout (in-memory only). */
        internal fun serialize(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeByte(BLOB_VERSION)
                d.writeUTF(config.baseUrl)
                d.writeUTF(config.username)
                d.writeUTF(config.appPassword)
                d.writeUTF(config.chatRoot)
                d.writeUTF(chatId)
                d.writeUTF(communityName)
            }
            return out.toByteArray()
        }

        /** Deserialize a blob written by [serialize], or `null` if the layout/version is wrong. */
        internal fun deserialize(bytes: ByteArray): StoredConnection? =
            try {
                DataInputStream(bytes.inputStream()).use { d ->
                    if (d.readByte().toInt() != BLOB_VERSION) return null
                    val config =
                        ConnectionConfig(
                            baseUrl = d.readUTF(),
                            username = d.readUTF(),
                            appPassword = d.readUTF(),
                            chatRoot = d.readUTF(),
                        )
                    StoredConnection(config, chatId = d.readUTF(), communityName = d.readUTF())
                }
            } catch (_: java.io.IOException) {
                null
            }
    }
}

/**
 * The decoded contents of [ConnectionConfigStore]: the WebDAV [config], plus the joined community chat-id
 * and community name. Holds the secret app-password (inside [config], which redacts it in `toString`).
 */
internal data class StoredConnection(
    val config: ConnectionConfig,
    val chatId: String,
    val communityName: String,
)
