package org.openwebdav.messenger.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleReadPolicyTest {
    @Test
    fun selectsVisibleOrderTokensFromTheLatestList() {
        val latestRows = listOf(row("new", "0002"), row("newest", "0003"))

        assertEquals("0003", latestVisibleOrderToken(latestRows, listOf(0, 1)))
    }

    @Test
    fun returnsNullWhenVisibleIndicesContainNoMessages() {
        assertNull(latestVisibleOrderToken(emptyList(), listOf(0)))
    }

    private fun row(
        id: String,
        token: String,
    ) = ChatFeedViewModel.FeedRow(
        messageId = id,
        body = id,
        isMine = false,
        sendStatus = "SENT",
        orderToken = token,
    )
}
