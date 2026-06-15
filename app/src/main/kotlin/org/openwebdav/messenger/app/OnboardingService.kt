package org.openwebdav.messenger.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.invite.InviteCodec
import org.openwebdav.messenger.invite.InviteToken
import org.openwebdav.messenger.keystore.ChatKeyStorePort
import org.openwebdav.messenger.transport.ConnectionConfig
import java.security.MessageDigest

/**
 * The owner-create and member-join onboarding logic (`ui-chat-surface` plan Scenarios 1–4; arch note
 * Choice 4 — `CreateCommunityViewModel` / `JoinViewModel` delegate to this). Both roles converge on the
 * **same** device-local state (config + chat key + identity); the only difference is how that state
 * arrives — the owner types it (then the app mints a random key + chat-id), the member receives it inside
 * the invite. So both flows end the same way: persist config + Keystore-wrap the chat key + ensure the
 * identity + `EngineWiring.reconfigure(...)`.
 *
 * All blocking work (KDF/key-gen, Keystore wrap, identity load-or-create, invite gzip/base64) runs off the
 * UI thread on [ioDispatcher] (stack-notes Kotlin off-main-thread). The device-bound pieces sit behind
 * [Deps] so the flows are JVM-testable.
 *
 * **Security:** a non-HTTPS owner URL is refused **before any persist** ([CreateResult.CleartextRefused],
 * SC13); the app-password + chat key are Keystore-wrapped, never logged, never on the WebDAV disk (SC4);
 * the member-join path returns NO disk credentials to the caller — only the community name + chat-id — so
 * a join screen cannot surface them ([JoinResult.Joined] carries no URL/username/password/folder).
 */
internal class OnboardingService(
    private val deps: Deps,
    private val codec: InviteCodec = InviteCodec(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Owner create-community (Scenario 1): refuse a non-HTTPS URL; otherwise ensure the identity, resolve
     * the chat-root folder (auto-generate a hash-based name when blank, refuse an occupied folder), mint a
     * fresh random chat key + a fresh chat-id, create the folder on the WebDAV disk, persist config +
     * Keystore-wrap the key, auto-create the one community chat (= the chat-id just minted — there is no
     * separate create-chat step), and reconfigure the engine. Returns the joined-chat coordinates for
     * navigation to the feed.
     */
    suspend fun createCommunity(
        baseUrl: String,
        username: String,
        appPassword: String,
        chatRoot: String,
        communityName: String,
    ): CreateResult =
        withContext(ioDispatcher) {
            if (!isHttps(baseUrl)) return@withContext CreateResult.CleartextRefused
            val root = generateFolderName(baseUrl.trim(), communityName.trim())
            // Always nest under the shared parent folder so multiple communities coexist cleanly.
            val fullRoot = "$PARENT_FOLDER/$root"
            val config =
                ConnectionConfig(
                    baseUrl = baseUrl.trim(),
                    username = username.trim(),
                    appPassword = appPassword,
                    chatRoot = fullRoot,
                )
            // Check if the folder already exists and has content — refuse to write into an occupied folder.
            when (val folderCheck = deps.checkFolder(config, fullRoot)) {
                is FolderCheck.Occupied -> return@withContext CreateResult.FolderOccupied
                is FolderCheck.Error -> return@withContext CreateResult.FolderError(folderCheck.message)
                is FolderCheck.Ok -> { /* proceed */ }
            }
            val identity = deps.ensureIdentity()
            val chatId = deps.newChatId()
            val chatKey = deps.keySources().newRandomKey()
            persistAndReconfigure(config, chatId, communityName.trim(), chatKey, identity)
            CreateResult.Created(chatId, communityName.trim(), fullRoot)
        }

    /**
     * Member join (Scenarios 3–4): decode the invite (reject-don't-guess → [JoinResult.Invalid] for a
     * foreign/garbled token, never a crash), ensure the identity, import the carried raw chat key, persist
     * the disk config + Keystore-wrap the key silently, and reconfigure. The returned [JoinResult.Joined]
     * carries ONLY the community name + chat-id — never the disk URL/username/password/folder.
     */
    suspend fun joinFromInvite(inviteString: String): JoinResult =
        withContext(ioDispatcher) {
            when (val decoded = codec.decodeBlocking(inviteString)) {
                is InviteCodec.Result.Rejected -> JoinResult.Invalid
                is InviteCodec.Result.Decoded -> joinFromToken(decoded.token)
            }
        }

    private suspend fun joinFromToken(token: InviteToken): JoinResult {
        val config =
            ConnectionConfig(
                baseUrl = token.baseUrl,
                username = token.username,
                appPassword = token.appPassword,
                chatRoot = token.chatRoot,
            )
        // A member's invite must still carry an HTTPS disk; a cleartext URL inside the token is refused.
        if (!isHttps(config.baseUrl)) return JoinResult.Invalid
        val identity = deps.ensureIdentity()
        val rawKey = token.chatKey
        val chatKey = deps.keySources().importRawKey(rawKey)
        persistAndReconfigure(config, token.chatId, token.communityName, chatKey, identity)
        return JoinResult.Joined(token.chatId, token.communityName)
    }

    private fun persistAndReconfigure(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        chatKey: ChatKey,
        identity: Identity,
    ) {
        deps.chatKeyStore().store(chatId, chatKey)
        deps.saveConfig(config, chatId, communityName)
        deps.reconfigure(config, chatId, communityName, chatKey, identity)
    }

    private fun isHttps(url: String): Boolean = url.trim().lowercase().startsWith("https://")

    /** The device-bound seam — overridable in JVM tests (the production impl uses native crypto + Keystore). */
    internal interface Deps {
        fun keySources(): KeySources

        fun chatKeyStore(): ChatKeyStorePort

        /** Check whether [root] is safe for a new community (empty or non-existent) and create it via MKCOL. */
        suspend fun checkFolder(
            config: ConnectionConfig,
            root: String,
        ): FolderCheck

        /** Persist the config + joined-chat marker (Keystore-wrapped in production; SC4). */
        fun saveConfig(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
        )

        suspend fun ensureIdentity(): Identity

        fun newChatId(): String

        fun reconfigure(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
            chatKey: ChatKey,
            identity: Identity,
        )
    }

    sealed interface FolderCheck {
        data object Ok : FolderCheck

        data object Occupied : FolderCheck

        data class Error(val message: String) : FolderCheck
    }

    /** Generate a collision-resistant folder name: `owdm-` + 16 hex chars of SHA-256(baseUrl + communityName). */
    private fun generateFolderName(
        baseUrl: String,
        communityName: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$baseUrl:$communityName".toByteArray(Charsets.UTF_8))
        val hex = digest.digest().take(8).joinToString("") { "%02x".format(it) }
        return "owdm-$hex"
    }

    /** Owner create-community outcome. */
    sealed interface CreateResult {
        /** Created the community + its one chat; [chatId] / [communityName] for navigation to the feed. */
        data class Created(
            val chatId: String,
            val communityName: String,
            val folderName: String,
        ) : CreateResult

        /** The URL was not HTTPS — refused before any persist (SC13). */
        data object CleartextRefused : CreateResult

        /** The auto-generated folder already contains files — refuse to write into an occupied space. */
        data object FolderOccupied : CreateResult

        /** Cannot access or create the folder on the disk (network, permissions, etc.). */
        data class FolderError(val message: String) : CreateResult
    }

    /** Member join outcome — carries NO disk credentials (Must not break: member never sees them). */
    sealed interface JoinResult {
        /** Joined; [chatId] / [communityName] for navigation. Disk URL/login/password/folder are NOT here. */
        data class Joined(val chatId: String, val communityName: String) : JoinResult

        /** The invite was not a valid `owdm1:` token (foreign QR / garbled / cleartext disk) — clean error. */
        data object Invalid : JoinResult
    }

    private companion object {
        /** Shared parent folder on the WebDAV disk — all community hash-folders live inside it. */
        const val PARENT_FOLDER = "openmsg"
    }
}
