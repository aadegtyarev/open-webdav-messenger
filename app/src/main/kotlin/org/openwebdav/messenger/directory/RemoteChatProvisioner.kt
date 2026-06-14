package org.openwebdav.messenger.directory

import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.keystore.ChatKeyStore
import org.openwebdav.messenger.keystore.ChatKeyStorePort

/**
 * The **remote-private-chat key provisioning seam** (`docs/features/x25519-identity_plan.md` → Contracts;
 * arch note (transient `.ai-dev/arch/x25519-identity_arch.md`, deleted after ship) choice 2). A thin coordinator that establishes a private
 * chat with a directory-discovered peer **using public keys alone — no secret exchanged over any channel**:
 *
 *  local [Identity] (its box secret) + a peer's box public key (from a verified [DirectoryEntry]) + a
 *  chat-id → [IdentityCrypto.deriveRemoteChatKey] → [ChatKeyStore.store].
 *
 * It composes three already-existing modules downward (`identity` + the `directory` verified-peer type +
 * `keystore`); the load-bearing layering constraint is that `identity` (the pure-crypto substrate) must
 * **never** depend on `directory` — so this seam sits **above** both, in the `directory` package, which
 * already legitimately depends on `identity` and gains a sane downward edge to `keystore` here.
 *
 * **Idempotent / re-provisioning-safe:** the derivation is deterministic from (myBoxSecret, peerBoxPublic,
 * chatId), so re-provisioning the same peer + chat-id derives the identical key. [ChatKeyStore.store]
 * **overwrites by chat-id** (source-agnostic — it does not know a key's origin), so the logical result is
 * the same stored key regardless of who provisions first or how often (the stored blob bytes differ per
 * wrap because of the fresh Keystore IV, but the unwrapped key is identical). Because `store` overwrites
 * by chat-id, a caller (a future chat-creation / invite surface) MUST NOT provision a chat-id that already
 * names a different-source chat (passphrase / random / known) — that would silently replace its key.
 *
 * **Typed result, never throws (C8 posture):** a native DH/KDF failure degrades to [ProvisionOutcome.Failed]
 * rather than propagating an uncaught exception, consistent with `DirectoryService.publishEntry`'s mapping
 * of a native seal failure to a typed outcome. The catch is narrow (the native-crypto failure at the
 * derivation boundary), so unrelated bugs still surface.
 */
class RemoteChatProvisioner(
    private val identityCrypto: IdentityCrypto,
    private val chatKeyStore: ChatKeyStorePort,
) {
    /**
     * Provision the per-chat key for the chat [chatId] with the peer described by [peer] (the verified
     * directory record carrying the peer's box public key), using [localIdentity]'s box secret. Derives
     * the chat-id-bound DH key and stores it under [chatId]; returns a typed [ProvisionOutcome].
     *
     * The local box-secret copy pulled from [localIdentity] is zeroized after the derive (A2 — identity
     * secret keys never linger; only the peer's public box key and the public chat-id are the other inputs).
     */
    fun provision(
        localIdentity: Identity,
        peer: DirectoryEntry,
        chatId: String,
    ): ProvisionOutcome {
        val myBoxSecret = localIdentity.copyBoxSecret()
        val peerBoxPublic = peer.copyBoxPublicKey()
        return try {
            val key = identityCrypto.deriveRemoteChatKey(myBoxSecret, peerBoxPublic, chatId)
            chatKeyStore.store(chatId, key)
            ProvisionOutcome.Provisioned(chatId)
        } catch (e: IllegalStateException) {
            // Narrow: a native DH/KDF (boxBeforeNm / keyedHash) failure surfaces as an IllegalStateException
            // from the LazySodiumCrypto check() guards — degrade to a typed failure, no uncaught throw.
            ProvisionOutcome.Failed(e.message ?: "native crypto failure")
        } finally {
            myBoxSecret.fill(0)
        }
    }
}

/**
 * The typed result of [RemoteChatProvisioner.provision] — like the directory `PublishOutcome` and the
 * crypto `OpenResult`, **never thrown**: provisioning a remote-chat key always returns a typed value.
 */
sealed interface ProvisionOutcome {
    /** The chat key was derived and stored under [chatId]; [ChatKeyStore.load] will return it. */
    data class Provisioned(val chatId: String) : ProvisionOutcome

    /**
     * Provisioning could not complete because the native DH/KDF derivation failed (C8 — no uncaught
     * throw on the provisioning path). [reason] is a short diagnostic, never surfaced as a crash.
     */
    data class Failed(val reason: String) : ProvisionOutcome
}
