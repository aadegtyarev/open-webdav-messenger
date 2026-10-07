package org.openwebdav.messenger.ui.feed

/** Select the newest currently-visible message from the latest rendered feed rows. */
internal fun latestVisibleOrderToken(
    latestItems: List<Any>,
    visibleIndices: List<Int>,
): String? =
    visibleIndices
        .mapNotNull { latestItems.getOrNull(it) as? ChatFeedViewModel.FeedRow }
        .maxOfOrNull { it.orderToken }
