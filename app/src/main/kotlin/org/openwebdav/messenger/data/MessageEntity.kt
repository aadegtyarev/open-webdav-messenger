package org.openwebdav.messenger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A locally-persisted message row (`docs/features/sync_plan.md` → Local history; scenario 6).
 *
 * The primary key is the §2 **message-id** (`order-token "~" content-hash`,
 * `docs/protocol/webdav-layout.md` §2): a content-addressed name is globally unambiguous and is the
 * dedup key, so re-inserting the same message-id is an idempotent no-op (scenario 4 / §9.3 step 3).
 * Ordering is by [orderToken] — the lexicographically-sortable §4 prefix of the id, NOT by
 * [sendTimestampMillis] (which is best-effort display-only, §4).
 *
 * The row holds the **decrypted, signature-verified** message fields. Per the threat model this means
 * plaintext history lives at rest in the app-private Room DB (never on the WebDAV disk) — the same
 * device-local persistence tier as the Keystore-wrapped keys (stack-notes Platform filesystem layout).
 *
 * @property messageId §2 file name = this message's only id (§8.6). Primary key / dedup key.
 * @property chatId the chat this message belongs to (§8.4/§8.5 tag 0x01).
 * @property orderToken §4 order-token — the sort key and the cursor coordinate (§9.3).
 * @property senderSignPub the §8.2 sender Ed25519 public key (32 bytes), hex-encoded for storage.
 * @property kind [KIND_TEXT] or [KIND_REACTION] (§8.2).
 * @property body text body (text kind only); `null` for a reaction.
 * @property replyTo §8.4 tag 0x02 — the §2 name of a quoted message; `null` if not a reply.
 * @property targetId §8.5 tag 0x02 — the §2 name of the reacted-to message (reaction kind only).
 * @property reactionIndex §8.5 tag 0x03 — 0..4 (reaction kind only); `null` for text.
 * @property sendTimestampMillis §8.4 tag 0x04 — best-effort display-only wall-clock (text kind only).
 * @property receivedAtMillis local clock when this row was persisted (diagnostics / "new since" UI).
 * @property sendStatus local send state: [STATUS_SENDING], [STATUS_SENT], or [STATUS_FAILED].
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["chatId", "orderToken"])],
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val orderToken: String,
    val senderSignPub: String,
    val kind: Int,
    val body: String?,
    val replyTo: String?,
    val targetId: String?,
    val reactionIndex: Int?,
    val sendTimestampMillis: Long?,
    val receivedAtMillis: Long,
    val sendStatus: String = STATUS_SENT,
) {
    companion object {
        /** §8.2 kind 0x01 — a text message. */
        const val KIND_TEXT = 1

        /** §8.2 kind 0x02 — a reaction message. */
        const val KIND_REACTION = 2

        const val STATUS_SENT = "SENT"
        const val STATUS_SENDING = "SENDING"
        const val STATUS_FAILED = "FAILED"
    }
}
