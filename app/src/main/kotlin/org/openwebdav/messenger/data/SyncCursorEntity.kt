package org.openwebdav.messenger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The per-chat sync cursor (`docs/protocol/webdav-layout.md` §9.3; `docs/features/sync_plan.md`
 * scenario 8). [orderToken] is the coordinate up to which this device has **successfully
 * fetched-and-persisted** `log/` entries for [chatId] — the resume point.
 *
 * It is stored **locally only** (Room, never on the WebDAV disk, §9.3). The cursor advances ONLY
 * over entries that were fetched and persisted, so a 429/Doze interruption mid-fetch leaves it
 * pointing before the first unfetched entry and the next cycle resumes there with no loss inside the
 * window (the single invariant the arch note §32 flags as not-to-get-wrong).
 *
 * @property chatId the chat this cursor tracks (primary key — one cursor per chat).
 * @property orderToken the §4 order-token high-water mark; messages with a strictly-greater
 *   order-token are still unfetched. An empty string means "no cursor yet" (fetch from the start
 *   of the window).
 */
@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val chatId: String,
    val orderToken: String,
)
