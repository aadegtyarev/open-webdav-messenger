package org.openwebdav.messenger.sync

import org.openwebdav.messenger.protocol.Base32
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Time-based retention pruning of old WebDAV entries per `docs/protocol/webdav-layout.md` §1.4.
 *
 * Scans the shared `log/` and per-member `changes/` collections under a chat-root and DELETEs
 * entries older than [window] (default 14 days). The timestamp is extracted from the order-token
 * prefix of each entry's file name via [Base32.decodeBase32HexFixed].
 *
 * Pruning is **disk-only** — local Room history is never touched. Entries whose timestamp cannot
 * be parsed (malformed name) are left in place. Transport errors during listing are silently
 * skipped (pruning is best-effort; the next poll cycle retries).
 *
 * @property transport the WebDAV transport for listing and deleting entries.
 * @property clock the clock used to determine "now" — injectable for tests.
 * @property window entries older than this duration are deleted (default 14 days).
 */
internal class RetentionPruner(
    private val transport: WebDavTransport,
    private val clock: Clock = Clock.systemUTC(),
    var window: Duration = 14.days,
) {
    companion object {
        /**
         * Extract the unix-millis timestamp from the first 11 characters of an order-token.
         * The order-token format (§4) is `ts-millis(11)-sender-tag(8)-seq(8)` = 29 chars.
         * Returns `null` if the token is too short or contains an invalid Base32hex character.
         */
        fun decodeTimestamp(orderToken: String): Long? {
            if (orderToken.length < 11) return null
            val tsEncoded = orderToken.substring(0, 11)
            return try {
                Base32.decodeBase32HexFixed(tsEncoded)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Returns `true` if [tsMillis] is older than [window] relative to [clock.now].
         */
        fun isExpired(
            tsMillis: Long,
            clock: Clock,
            window: Duration,
        ): Boolean {
            val now = clock.instant().toEpochMilli()
            return now - tsMillis > window.inWholeMilliseconds
        }
    }

    /**
     * Prune old log entries and change-index entries for [chatId].
     * Best-effort: listing or deletion failures are silently skipped.
     */
    suspend fun pruneChat(chatId: String) {
        pruneLogEntries(chatId)
        pruneChangeEntries()
    }

    private suspend fun pruneLogEntries(chatId: String) {
        val logPath = ChatPaths.logDir(chatId)
        when (val result = transport.list(logPath)) {
            is WebDavResult.Success -> {
                for (entry in result.value) {
                    if (entry.isCollection) continue
                    val split = MessageId.splitMessageId(entry.name) ?: continue
                    val orderToken = split.first
                    val tsMillis = decodeTimestamp(orderToken) ?: continue
                    if (RetentionPruner.isExpired(tsMillis, clock, window)) {
                        transport.delete("$logPath/${entry.name}")
                    }
                }
            }
            else -> { /* listing failed — skip pruning this cycle */ }
        }
    }

    /**
     * List the `changes/` root, then for each member-index subdirectory list and prune
     * change entries whose embedded order-token is older than the window. Change-entry
     * file names have the format `chat-tag~order-token` (§9.2); the order-token is
     * extracted from the segment after the `~`.
     */
    private suspend fun pruneChangeEntries() {
        when (val changesResult = transport.list(ChatPaths.CHANGES)) {
            is WebDavResult.Success -> {
                for (memberDir in changesResult.value) {
                    if (!memberDir.isCollection) continue
                    val memberPath = "${ChatPaths.CHANGES}/${memberDir.name}"
                    when (val entriesResult = transport.list(memberPath)) {
                        is WebDavResult.Success -> {
                            for (entry in entriesResult.value) {
                                if (entry.isCollection) continue
                                val sepIdx = entry.name.indexOf(MessageId.SEPARATOR)
                                if (sepIdx <= 0) continue
                                val orderToken = entry.name.substring(sepIdx + 1)
                                val tsMillis = RetentionPruner.decodeTimestamp(orderToken) ?: continue
                                if (RetentionPruner.isExpired(tsMillis, clock, window)) {
                                    transport.delete("$memberPath/${entry.name}")
                                }
                            }
                        }
                        else -> { /* listing failed — skip */ }
                    }
                }
            }
            else -> { /* listing failed — skip */ }
        }
    }
}
