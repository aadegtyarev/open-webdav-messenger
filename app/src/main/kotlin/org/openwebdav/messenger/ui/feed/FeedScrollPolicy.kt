package org.openwebdav.messenger.ui.feed

/** Scroll to the tail only for an append observed while the prior viewport was already near it. */
internal fun shouldAutoScrollAfterAppend(
    wasAtBottomBeforeAppend: Boolean,
    previousItemCount: Int,
    newItemCount: Int,
): Boolean = wasAtBottomBeforeAppend && newItemCount > previousItemCount

/** Stores the last laid-out viewport state so an append never re-measures its pre-append position. */
internal class FeedAppendPolicy(initialItemCount: Int) {
    var itemCount: Int = initialItemCount
        private set

    private var wasNearBottom = false

    fun onViewportChanged(isNearBottom: Boolean) {
        wasNearBottom = isNearBottom
    }

    fun onDatasetChanged(newItemCount: Int): Boolean {
        val shouldFollow = shouldAutoScrollAfterAppend(wasNearBottom, itemCount, newItemCount)
        itemCount = newItemCount
        return shouldFollow
    }
}
