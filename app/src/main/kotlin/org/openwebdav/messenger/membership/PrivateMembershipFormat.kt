package org.openwebdav.messenger.membership

/** Wire bounds for append-only private membership claims. */
internal object PrivateMembershipFormat {
    const val COLLECTION = "private-membership"
    const val DOMAIN = "owdm/private-membership-claim"
    const val VERSION: Byte = 1
    const val SIGNING_KEY_BYTES = 32
    const val BOX_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64
    const val MAX_CHAT_ID_BYTES = 96
    const val MAX_DISPLAY_NAME_BYTES = 256
    const val NAME_BYTES = 2
    const val CLAIM_HEADER_BYTES = 4 + 1 + 2 + 1 + 2 + 2
    const val MAX_CLAIM_BYTES =
        CLAIM_HEADER_BYTES + MAX_CHAT_ID_BYTES + SIGNING_KEY_BYTES + BOX_KEY_BYTES +
            MAX_DISPLAY_NAME_BYTES + SIGNATURE_BYTES
    const val MAX_FILE_BYTES = 600
    const val MAX_LISTED_ENTRIES = 256
    const val ENTRY_NAME_BYTES = 32
    const val MAGIC = "OWPM"
}
