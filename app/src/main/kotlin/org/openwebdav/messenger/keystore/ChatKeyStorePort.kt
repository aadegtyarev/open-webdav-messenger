package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.crypto.ChatKey

/**
 * The narrow store/load seam over per-chat [ChatKey] persistence, so a coordinator that needs to persist
 * a key (the remote-chat key provisioning seam in the `directory` package) depends on this small surface
 * rather than the device-bound concrete [ChatKeyStore]. [ChatKeyStore] is the production implementation
 * (Android Keystore-backed); a JVM in-memory implementation stands in for off-device unit tests, since
 * the real store is exercised only under `connectedAndroidTest` (Keystore is device-backed —
 * `docs/stack-notes.md` → Android Keystore).
 *
 * Exposes only the two operations a provisioning coordinator uses; the wider [ChatKeyStore] surface
 * (`has` / `remove` / `rawStoredBlob`) stays off this seam.
 */
interface ChatKeyStorePort {
    /** Wrap and persist [chatKey] for [chatId], overwriting any existing key for that chat. */
    fun store(
        chatId: String,
        chatKey: ChatKey,
    )

    /** Load the stored key for [chatId], or `null` if none is stored or the blob is unrecoverable. */
    fun load(chatId: String): ChatKey?
}
