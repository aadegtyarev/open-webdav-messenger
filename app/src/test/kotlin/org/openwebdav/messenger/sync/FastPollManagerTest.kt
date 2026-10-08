package org.openwebdav.messenger.sync

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [FastPollManager] toggle state transitions and default interval.
 *
 * These tests exercise the SharedPreferences-backed enable/disable/isEnabled/intervalSeconds
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

        workManager.cancelAllWork().result.get()
        FastPollManager.disable(context, workManager)
        workManager.cancelAllWork().result.get()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        FastPollManager.disable(context, workManager)
        workManager.cancelAllWork().result.get()
        shadowOf(Looper.getMainLooper()).idle()
        SyncRunner.install(SyncRunner { CycleOutcome(0, 0, backedOff = false) })
    }

    @Test
    fun `default state is disabled`() {
        assertFalse(FastPollManager.isEnabled(context))
    }

    @Test
    fun `enable sets state to true and persists`() {
        FastPollManager.enable(context, workManager, intervalSeconds = 420)

        assertTrue(FastPollManager.isEnabled(context))
        assertEquals(420L, FastPollManager.intervalSeconds(context))
    }

    @Test
    fun `enable retains periodic work as fallback`() {
        FastPollManager.enable(context, workManager, intervalSeconds = 420)
        shadowOf(Looper.getMainLooper()).idle()

        val work = workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get()
        assertTrue("Periodic fallback was not retained: $work", work.any { it.state != WorkInfo.State.CANCELLED })
    }

    @Test
    fun `disable sets state to false`() {
        FastPollManager.enable(context, workManager)
        FastPollManager.disable(context, workManager)

        assertFalse(FastPollManager.isEnabled(context))
    }

    @Test
    fun `default interval is 300 seconds`() {
        assertEquals(300L, FastPollManager.DEFAULT_INTERVAL)
    }

    @Test
    fun `interval persists across reads without enable`() {
        // intervalSeconds reads the stored value; the default is 300
        assertEquals(300L, FastPollManager.intervalSeconds(context))

        // After enabling with a custom interval, it persists
        FastPollManager.enable(context, workManager, intervalSeconds = 180)
        assertEquals(180L, FastPollManager.intervalSeconds(context))

        // After disable, the stored interval is preserved
        FastPollManager.disable(context, workManager)
        assertEquals(180L, FastPollManager.intervalSeconds(context))
    }

    @Test
    fun `restoreIfEnabled is a no-op when disabled`() {
        // Should not throw and should not change state
        FastPollManager.restoreIfEnabled(context, workManager)
        assertFalse(FastPollManager.isEnabled(context))
    }

    @Test
    fun `enable with default interval uses 300 seconds`() {
        FastPollManager.enable(context, workManager)

        assertTrue(FastPollManager.isEnabled(context))
        assertEquals(300L, FastPollManager.intervalSeconds(context))
    }
}
