package org.openwebdav.messenger.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [FastPollManager] toggle state transitions and default interval.
 *
 * These tests exercise the SharedPreferences-backed enable/disable/isEnabled/intervalMinutes
 * methods with a Robolectric-supplied Context. Service lifecycle tests are deferred to
 * `connectedAndroidTest` (needs an emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FastPollManagerTest {
    private lateinit var context: android.content.Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config =
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)

        // Start clean: disable fast polling before each test
        FastPollManager.disable(context, workManager)
    }

    @Test
    fun `default state is disabled`() {
        assertFalse(FastPollManager.isEnabled(context))
    }

    @Test
    fun `enable sets state to true and persists`() {
        FastPollManager.enable(context, workManager, intervalMinutes = 7)

        assertTrue(FastPollManager.isEnabled(context))
        assertEquals(7L, FastPollManager.intervalMinutes(context))
    }

    @Test
    fun `disable sets state to false`() {
        FastPollManager.enable(context, workManager)
        FastPollManager.disable(context, workManager)

        assertFalse(FastPollManager.isEnabled(context))
    }

    @Test
    fun `default interval is 5 minutes`() {
        assertEquals(5L, FastPollManager.DEFAULT_INTERVAL)
    }

    @Test
    fun `interval persists across reads without enable`() {
        // intervalMinutes reads the stored value; the default is 5
        assertEquals(5L, FastPollManager.intervalMinutes(context))

        // After enabling with a custom interval, it persists
        FastPollManager.enable(context, workManager, intervalMinutes = 3)
        assertEquals(3L, FastPollManager.intervalMinutes(context))

        // After disable, the stored interval is preserved
        FastPollManager.disable(context, workManager)
        assertEquals(3L, FastPollManager.intervalMinutes(context))
    }

    @Test
    fun `restoreIfEnabled is a no-op when disabled`() {
        // Should not throw and should not change state
        FastPollManager.restoreIfEnabled(context, workManager)
        assertFalse(FastPollManager.isEnabled(context))
    }

    @Test
    fun `enable with default interval uses 5 minutes`() {
        FastPollManager.enable(context, workManager)

        assertTrue(FastPollManager.isEnabled(context))
        assertEquals(5L, FastPollManager.intervalMinutes(context))
    }
}
