package org.openwebdav.messenger.keystore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ChatRegistryTest {
    @Test
    fun locally_created_private_marker_and_observed_peer_survive_registry_reload() {
        val registry = ChatRegistry(RuntimeEnvironment.getApplication())
        registry.clear("self-room-test")
        registry.add(
            "self-room-test",
            ChatRegistry.Entry("private-room", "Room", "group", "private", locallyCreatedPrivate = true),
        )

        val created = ChatRegistry(RuntimeEnvironment.getApplication()).all("self-room-test").single()
        assertTrue(created.locallyCreatedPrivate)
        assertFalse(created.nonSelfMemberObserved)

        registry.markNonSelfMemberObserved("self-room-test", "private-room")
        val observed = ChatRegistry(RuntimeEnvironment.getApplication()).all("self-room-test").single()
        assertTrue(observed.locallyCreatedPrivate)
        assertTrue(observed.nonSelfMemberObserved)
        registry.clear("self-room-test")
    }

    @Test
    fun unrelated_add_cannot_revert_sticky_non_self_observation() {
        val context = RuntimeEnvironment.getApplication()
        val communityId = "self-room-interleave-test"
        val addRead = CountDownLatch(1)
        val releaseAdd = CountDownLatch(1)
        val observedRead = CountDownLatch(1)
        val releaseObservation = CountDownLatch(1)
        val registry = ChatRegistry(context)
        registry.replace(communityId, listOf(ChatRegistry.Entry("private-room", "Room", "group", "private", true)))
        val addingRegistry =
            ChatRegistry(context).apply {
                afterAddReadForTest = {
                    addRead.countDown()
                    check(releaseAdd.await(5, TimeUnit.SECONDS))
                }
            }
        val observingRegistry =
            ChatRegistry(context).apply {
                afterObservedReadForTest = {
                    observedRead.countDown()
                    check(releaseObservation.await(5, TimeUnit.SECONDS))
                }
            }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val add = workers.submit { addingRegistry.add(communityId, ChatRegistry.Entry("other", "Other")) }
            assertTrue(addRead.await(5, TimeUnit.SECONDS))
            val observe = workers.submit { observingRegistry.markNonSelfMemberObserved(communityId, "private-room") }
            val observeReadConcurrently = observedRead.await(100, TimeUnit.MILLISECONDS)
            if (observeReadConcurrently) releaseObservation.countDown()
            if (observeReadConcurrently) observe.get(5, TimeUnit.SECONDS)
            releaseAdd.countDown()
            add.get(5, TimeUnit.SECONDS)
            if (!observeReadConcurrently) {
                releaseObservation.countDown()
                observe.get(5, TimeUnit.SECONDS)
            }
            val rows = registry.all(communityId)
            assertTrue(rows.any { it.id == "other" })
            assertTrue(rows.single { it.id == "private-room" }.nonSelfMemberObserved)
        } finally {
            releaseAdd.countDown()
            releaseObservation.countDown()
            workers.shutdownNow()
            registry.clear(communityId)
        }
    }

    @Test
    fun imported_or_legacy_registry_rows_are_not_self_only_eligible() {
        val registry = ChatRegistry(RuntimeEnvironment.getApplication())
        registry.clear("self-room-import-test")
        registry.add("self-room-import-test", ChatRegistry.Entry("joined", "Joined", "group", "private"))

        val imported = ChatRegistry(RuntimeEnvironment.getApplication()).all("self-room-import-test").single()
        assertFalse(imported.locallyCreatedPrivate)
        assertFalse(imported.nonSelfMemberObserved)
        registry.clear("self-room-import-test")
    }
}
