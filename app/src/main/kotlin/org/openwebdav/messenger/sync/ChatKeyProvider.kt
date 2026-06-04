package org.openwebdav.messenger.sync

import org.openwebdav.messenger.crypto.ChatKey

/**
 * Supplies the per-chat [ChatKey] for a poll cycle. The engine calls [keyFor] **once per chat per
 * cycle** and holds the result while it opens every new envelope in that chat — so the memory-hard
 * Argon2id KDF is NOT re-run per message (`docs/features/sync_plan.md` scenario 7; recorded crypto
 * downstream expectation 2026-06-03; stack-notes Crypto "not re-run per message").
 *
 * The production implementation wraps `keystore.ChatKeyStore.load(chatId)` (which holds the derived
 * key in memory after one Argon2id derivation). Returning `null` means the key is unavailable for that
 * chat this cycle — the engine skips that chat (no crash, no partial decrypt).
 */
fun interface ChatKeyProvider {
    /** The [ChatKey] for [chatId], or `null` if unavailable (chat skipped this cycle). Called once per chat. */
    fun keyFor(chatId: String): ChatKey?
}
