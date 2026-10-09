package org.openwebdav.messenger.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.CreationPolicySettingsState
import org.openwebdav.messenger.app.CreationPolicyUpdateResult
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class CreationPolicySettingsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun verified_host_sees_independent_toggles_and_commits_only_confirmed_values() {
        val state = AtomicReference<CreationPolicySettingsState>(CreationPolicySettingsState.DefaultAllowed)
        val written = AtomicReference<Pair<Boolean, Boolean>>()
        composeRule.setContent {
            CreationPolicySettingsSection(
                "community",
                true,
                SnackbarHostState(),
                readPolicy = { state.get() },
                updatePolicy = { public, private ->
                    written.set(public to private)
                    state.set(CreationPolicySettingsState.Policy(public, private, 1))
                    CreationPolicyUpdateResult.Saved
                },
            )
        }
        val privateSwitch = composeRule.onNodeWithContentDescription("Allow private group chats")
        composeRule.onNodeWithContentDescription("Allow public group chats").assertIsOn()
        privateSwitch.assertIsOn()
        privateSwitch.performClick()
        composeRule.waitUntil(5_000) { written.get() != null }
        assertEquals(true to false, written.get())
    }

    @Test
    fun member_sees_read_only_policy_without_switches() {
        composeRule.setContent {
            CreationPolicySettingsSection(
                "community",
                false,
                SnackbarHostState(),
                readPolicy = { CreationPolicySettingsState.DefaultAllowed },
                updatePolicy = { _, _ -> error("member must not update policy") },
            )
        }
        composeRule.onNodeWithText("Only the verified community host can change this policy.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Allow public group chats").assertDoesNotExist()
    }

    @Test
    fun unavailable_policy_offers_retry_without_exposing_toggles() {
        val reads = AtomicInteger()
        composeRule.setContent {
            CreationPolicySettingsSection(
                "community",
                true,
                SnackbarHostState(),
                readPolicy = {
                    if (reads.incrementAndGet() == 1) {
                        CreationPolicySettingsState.Unavailable
                    } else {
                        CreationPolicySettingsState.DefaultAllowed
                    }
                },
                updatePolicy = { _, _ -> CreationPolicyUpdateResult.Saved },
            )
        }
        composeRule.waitUntil(5_000) { reads.get() >= 1 }
        composeRule.onNodeWithText(
            "Policy or the complete origin list is unavailable. The previous policy remains active.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitUntil(5_000) { reads.get() >= 2 }
        composeRule.onNodeWithContentDescription("Allow public group chats").assertIsOn()
    }
}
