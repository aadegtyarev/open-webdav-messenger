package org.openwebdav.messenger.ui.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedScrollPolicyTest {
    @Test
    fun followsAnAppendWhenThePreviousViewportWasAtTheTail() {
        assertTrue(shouldAutoScrollAfterAppend(wasAtBottomBeforeAppend = true, previousItemCount = 8, newItemCount = 9))
    }

    @Test
    fun preservesHistoryViewportWhenThePreviousViewportWasAboveTheTail() {
        assertFalse(shouldAutoScrollAfterAppend(wasAtBottomBeforeAppend = false, previousItemCount = 8, newItemCount = 9))
    }

    @Test
    fun doesNotScrollWhenItemsWereRemovedOrUnchanged() {
        assertFalse(shouldAutoScrollAfterAppend(wasAtBottomBeforeAppend = true, previousItemCount = 8, newItemCount = 8))
        assertFalse(shouldAutoScrollAfterAppend(wasAtBottomBeforeAppend = true, previousItemCount = 8, newItemCount = 7))
    }
}
