package org.openwebdav.messenger.message

import org.openwebdav.messenger.identity.PublicIdentity

/**
 * The structured plaintext that lives INSIDE the §5.1 AEAD ciphertext-blob
 * (`docs/protocol/webdav-layout.md` §8). A `Message` is the typed view a reader gets after
 * AEAD-`open` + deserialize + signature-verify; a writer builds one, then it is serialized, signed,
 * and sealed. The sum type is `{TextMessage, ReactionMessage}` — the two `kind` values fixed in §8.2
 * (`0x01 text` / `0x02 reaction`); future kinds (edit/delete/system) arrive under a new
 * `msg-format-version` (§8.1, reject-don't-guess), they are NOT modelled here.
 *
 * A message carries **no inner self-id** (§8.6, corrected §8): its identity IS the outer §2
 * content-addressed file name of the envelope that seals it, assigned at seal time. References to OTHER
 * messages (`reply-to` / `target-id`) carry those other messages' full §2 file names.
 *
 * Every kind carries (§8.2):
 *  - [chatId] — the chat this message belongs to (tag `0x01`).
 *  - [sender] — the sender's public identity; its Ed25519 [PublicIdentity.signPub] is serialized as the
 *    fixed-prefix `sender-id-pubkey` (§8.2) and is the key the signature verifies against (§8.3).
 */
sealed interface Message {
    /** §8.4/§8.5 tag `0x01`: the chat identifier this message belongs to. */
    val chatId: String

    /** §8.2: the sender's public identity; the Ed25519 public key is the signed `sender-id-pubkey`. */
    val sender: PublicIdentity
}

/**
 * A `text` message (§8.4, `kind = 0x01`): a chat body plus an optional reply reference and a
 * best-effort display timestamp.
 *
 * @property replyTo §8.4 tag `0x02` (optional): the FULL §2 file name (`order-token "~" content-hash`)
 *   of the message this text quotes; `null` = not a reply. May reference an as-yet-undelivered target
 *   (§4 causality) — a well-formed reference to a not-yet-received message is valid; resolution is a
 *   sync/UI concern, not a parse error. A malformed (non-§2-grammar) value is rejected on parse.
 * @property body §8.4 tag `0x03` (required): UTF-8 text = plain text + the supported Markdown subset,
 *   carried RAW (no rendering, no normalization — rendering is the UI feature).
 * @property sendTimestampMillis §8.4 tag `0x04` (required): the sender's wall-clock at send, unix-millis.
 *   DISPLAY-ONLY / best-effort — NOT trusted, NOT used for ordering (the §4 order-token orders).
 */
data class TextMessage(
    override val chatId: String,
    override val sender: PublicIdentity,
    val replyTo: String?,
    val body: String,
    val sendTimestampMillis: Long,
) : Message

/**
 * A `reaction` message (§8.5, `kind = 0x02`): its own message carrying the target it reacts to and an
 * index into the fixed 5-reaction set. A reaction is NOT a field on a text message — it is a first-class
 * message kind, content-addressed like any other (§8.6).
 *
 * @property targetId §8.5 tag `0x02` (required): the FULL §2 file name (`order-token "~" content-hash`)
 *   of the message being reacted to. May reference an as-yet-unseen message (§4 causality) — a
 *   well-formed reference to a not-yet-received message is valid; applying it to a missing target is a
 *   sync/UI concern, NOT a parse error. A malformed (non-§2-grammar) value is rejected on parse.
 * @property reactionIndex §8.5 tag `0x03` (required): a value in the closed range `0..4` (the fixed
 *   5-reaction set). An index ∉ `0..4` is a typed rejection on parse (§8.1). The concrete glyph per
 *   index is a UI concern and is NOT fixed here.
 */
data class ReactionMessage(
    override val chatId: String,
    override val sender: PublicIdentity,
    val targetId: String,
    val reactionIndex: Int,
) : Message {
    init {
        require(reactionIndex in MIN_REACTION_INDEX..MAX_REACTION_INDEX) {
            "reaction-index must be in $MIN_REACTION_INDEX..$MAX_REACTION_INDEX (the fixed 5-reaction set, §8.5)"
        }
    }

    companion object {
        /** §8.5: the fixed 5-reaction set is the closed index range `0..4`. */
        const val MIN_REACTION_INDEX = 0
        const val MAX_REACTION_INDEX = 4
    }
}
