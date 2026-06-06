package org.openwebdav.messenger.directory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.keystore.ChatKeyStorePort
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM unit + interaction tests for the remote-private-chat key provisioning seam
 * ([RemoteChatProvisioner]) — `docs/features/x25519-identity_plan.md` Test plan. Run against real
 * lazysodium-java (system libsodium); the device-bound `ChatKeyStore` is stood in by an in-memory
 * [InMemoryChatKeyStore] (the store seam [ChatKeyStorePort]), since the real Keystore-backed store is
 * exercised under `connectedAndroidTest` only. All NEW tests; no existing test is touched.
 */
class RemoteChatProvisionerTest {
    private val native = DirectoryTestSupport.native()
    private val identityCrypto = IdentityCrypto(native)

    private fun newIdentity(): Identity = identityCrypto.generateIdentity()

    /** Build a verified-peer [DirectoryEntry] from an identity's two public keys (as the directory returns). */
    private fun peerOf(identity: Identity): DirectoryEntry =
        DirectoryEntry(
            displayName = "peer",
            signingPublicKey = identity.copySignPublic(),
            boxPublicKey = identity.copyBoxPublic(),
        )

    // provisioning_stores_a_loadable_key (wiring parity) — drive the SAME provisioning entry point
    // production uses (RemoteChatProvisioner.provision), then ChatKeyStore.load(chat-id) returns the
    // key the derivation produced. Not a hand-rolled store call.
    @Test
    fun provisioning_stores_a_loadable_key() {
        val store = InMemoryChatKeyStore()
        val provisioner = RemoteChatProvisioner(identityCrypto, store)
        val me = newIdentity()
        val peer = newIdentity()
        val chatId = "remote-chat-1"

        val outcome = provisioner.provision(me, peerOf(peer), chatId)
        assertEquals(ProvisionOutcome.Provisioned(chatId), outcome)

        // The store returns the derived key — which equals the independent deriveRemoteChatKey output.
        val expected = identityCrypto.deriveRemoteChatKey(me.copyBoxSecret(), peer.copyBoxPublic(), chatId)
        val loaded = store.load(chatId)
        assertTrue(loaded != null)
        assertArrayEquals(expected.export(), loaded!!.export())
    }

    // provisioning_degrades_on_native_crypto_failure — with a NativeCrypto that fails the DH derivation,
    // provisioning returns its typed failure (no uncaught throw), consistent with the C8 posture.
    @Test
    fun provisioning_degrades_on_native_crypto_failure() {
        val store = InMemoryChatKeyStore()
        val failingIdentity = IdentityCrypto(DhFailingNative(native))
        val provisioner = RemoteChatProvisioner(failingIdentity, store)
        val me = newIdentity()
        val peer = newIdentity()
        val chatId = "remote-chat-fails"

        val outcome = provisioner.provision(me, peerOf(peer), chatId)
        assertTrue("native DH failure must degrade to a typed Failed", outcome is ProvisionOutcome.Failed)
        // Nothing was stored on the failure path.
        assertNull(store.load(chatId))
    }

    // provision_idempotent_and_isolated — re-provisioning the same (peer, chat-id) yields the same
    // stored key; provisioning chat-id "x" does not disturb an already-stored key for chat-id "y".
    @Test
    fun provision_idempotent_and_isolated() {
        val store = InMemoryChatKeyStore()
        val provisioner = RemoteChatProvisioner(identityCrypto, store)
        val me = newIdentity()
        val peer = newIdentity()

        // A pre-existing key for a DIFFERENT chat-id (e.g. a passphrase/random chat) must stay untouched.
        val otherChatId = "other-chat-y"
        val otherKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 7 })
        store.store(otherChatId, otherKey)

        val chatId = "remote-chat-x"
        provisioner.provision(me, peerOf(peer), chatId)
        val firstLoad = store.load(chatId)!!.export()

        // Re-provisioning the same peer + chat-id is idempotent: the same deterministic key.
        provisioner.provision(me, peerOf(peer), chatId)
        val secondLoad = store.load(chatId)!!.export()
        assertArrayEquals(firstLoad, secondLoad)

        // The unrelated chat-id's key is untouched.
        assertArrayEquals(otherKey.export(), store.load(otherChatId)!!.export())
    }
}

/**
 * In-memory [ChatKeyStorePort] for JVM tests: holds the key bytes in a map keyed by chat-id, mirroring
 * the production [org.openwebdav.messenger.keystore.ChatKeyStore]'s overwrite-by-chat-id contract
 * (the Keystore wrap/unwrap is exercised under `connectedAndroidTest`). Thread-safe so the
 * concurrent-provision interaction scenario can drive it.
 */
internal class InMemoryChatKeyStore : ChatKeyStorePort {
    private val keys = ConcurrentHashMap<String, ByteArray>()

    override fun store(
        chatId: String,
        chatKey: ChatKey,
    ) {
        keys[chatId] = chatKey.export()
    }

    override fun load(chatId: String): ChatKey? = keys[chatId]?.let { ChatKey.fromBytes(it) }
}

/**
 * A [NativeCrypto] that delegates everything to [delegate] EXCEPT [boxBeforeNm], which throws the same
 * `IllegalStateException` a spurious `crypto_box_beforenm` native failure surfaces (the `check()` in
 * `LazySodiumCrypto.boxBeforeNm`) — so the provisioning seam's typed-failure path can be exercised
 * off-device (the C8 posture: native crypto failure → typed Failed, no uncaught throw).
 */
internal class DhFailingNative(private val delegate: NativeCrypto) : NativeCrypto by delegate {
    override fun boxBeforeNm(
        peerBoxPk: ByteArray,
        myBoxSk: ByteArray,
    ): ByteArray = throw IllegalStateException("crypto_box_beforenm failed")
}
