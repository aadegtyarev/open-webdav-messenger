package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedCommunityGroupCreationTest {
    @Test
    fun createsAndOpensWithTheSelectedCommunityGraphTransportAndIdentity() =
        runTest {
            val graphB = TestGraph("community-b", "transport-b", "identity-b")
            val contextB = TestContext("community-b", graphB)
            var activeCommunity = "community-a"
            var createdWith: TestContext? = null
            var openedWith: TestContext? = null

            val result =
                createGroupInSelectedCommunity(
                    communityId = "community-b",
                    activeCommunityId = activeCommunity,
                    activateCommunity = { selected ->
                        activeCommunity = selected
                        true
                    },
                    resolveContext = { selected -> contextB.takeIf { selected == activeCommunity } },
                    create = { context ->
                        createdWith = context
                        assertEquals("community-b", context.graph.communityId)
                        assertEquals("transport-b", context.graph.transport)
                        assertEquals("identity-b", context.graph.identity)
                        "group-b"
                    },
                    open = { context, chatId ->
                        openedWith = context
                        assertEquals("group-b", chatId)
                        context.communityId == "community-b"
                    },
                )

            assertEquals("group-b", result)
            assertSame(contextB, createdWith)
            assertSame(contextB, openedWith)
        }

    @Test
    fun activationFailureNeverCreatesOrOpensInThePreviousCommunity() =
        runTest {
            var createCalled = false
            var openCalled = false
            val result =
                createGroupInSelectedCommunity(
                    communityId = "community-b",
                    activeCommunityId = "community-a",
                    activateCommunity = { false },
                    resolveContext = { error("must not resolve old runtime") },
                    create = {
                        createCalled = true
                        "wrong-chat"
                    },
                    open = { _, _ ->
                        openCalled = true
                        true
                    },
                )

            assertNull(result)
            assertTrue(!createCalled && !openCalled)
        }

    private data class TestGraph(val communityId: String, val transport: String, val identity: String)

    private data class TestContext(val communityId: String, val graph: TestGraph)
}
