package org.openwebdav.messenger.ui.feed

/** Scroll to the tail only for an append observed while the prior viewport was already near it. */
internal fun shouldAutoScrollAfterAppend(
    wasAtBottomBeforeAppend: Boolean,
    previousItemCount: Int,
    newItemCount: Int,
): Boolean = wasAtBottomBeforeAppend && newItemCount > previousItemCount
