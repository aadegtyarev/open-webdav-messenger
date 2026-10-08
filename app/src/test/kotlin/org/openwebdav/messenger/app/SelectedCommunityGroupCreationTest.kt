package org.openwebdav.messenger.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

    @Test
    fun non_active_community_group_creation_finishes_inside_the_account_barrier() =
        runTest {
            val graphB = TestGraph("community-b", "transport-b", "identity-b")
            val contextB = TestContext("community-b", graphB)
            var activeCommunity = "community-a"

            val result =
                withTimeout(1_000) {
                    createGroupInSelectedCommunity(
                        communityId = "community-b",
                        activeCommunityId = activeCommunity,
                        activateCommunity = { selected ->
                            activeCommunity = selected
                            true
                        },
                        resolveContext = { selected -> contextB.takeIf { it.communityId == selected && activeCommunity == selected } },
                        create = { "group-b" },
                        open = { context, chatId -> context.communityId == "community-b" && chatId == "group-b" },
                    )
                }

            assertEquals("group-b", result)
            assertEquals("community-b", activeCommunity)
        }

    @Test
    fun later_general_request_stays_current_when_suspended_group_creation_resumes() =
        runTest {
            val requests = ChatOpenRequestCoordinator()
            val createToken = requests.begin()
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<String?>()
            val context = TestContext("community-a", TestGraph("community-a", "transport-a", "identity-a"))
            var activeCommunity = "community-a"
            var runtimeCommunity = "community-a"
            var revision = 0
            var mutations = 0
            var opens = 0

            val creation =
                async {
                    createGroupInSelectedCommunity(
                        communityId = "community-a",
                        activeCommunityId = activeCommunity,
                        isRuntimeCurrent = { requests.isCurrent(createToken) && runtimeCommunity == "community-a" },
                        activateCommunity = { false },
                        resolveContext = { context },
                        create = {
                            mutations++
                            publishStarted.complete(Unit)
                            finishPublish.await()
                        },
                        open = { _, _ ->
                            opens++
                            true
                        },
                    )
                }
            publishStarted.await()
            val mutationsBeforeGeneral = mutations
            val generalToken = requests.begin()

            finishPublish.complete("group-a")
            assertNull(creation.await())
            assertEquals("community-a", activeCommunity)
            assertEquals("community-a", runtimeCommunity)
            assertEquals(0, revision)
            assertEquals(mutationsBeforeGeneral, mutations)
            assertEquals(0, opens)
            assertTrue(
                requests.runIfCurrent(generalToken) {
                    activeCommunity = "community-b"
                    runtimeCommunity = "community-b"
                    revision++
                    true
                },
            )
            assertTrue(requests.isCurrent(generalToken))
            assertEquals("community-b", activeCommunity)
            assertEquals("community-b", runtimeCommunity)
            assertEquals(1, revision)
        }

    @Test
    fun cancellation_from_group_publication_propagates_through_creation() =
        runTest {
            val cancellation = CancellationException("publication cancelled")
            val context = TestContext("community-a", TestGraph("community-a", "transport-a", "identity-a"))
            try {
                createGroupInSelectedCommunity(
                    communityId = "community-a",
                    activeCommunityId = "community-a",
                    activateCommunity = { false },
                    resolveContext = { context },
                    create = {
                        bestEffortGroupPublication { throw cancellation }
                        "group-a"
                    },
                    open = { _, _ -> error("cancelled creation must not open") },
                )
                throw AssertionError("expected cancellation")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
        }

    private data class TestGraph(val communityId: String, val transport: String, val identity: String)

    private data class TestContext(val communityId: String, val graph: TestGraph)
}
