package org.openwebdav.messenger.ui.participants

import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.ui.MainActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParticipantContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun blank_private_chat_name_uses_existing_unavailable_and_provenance_labels() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val container = activity.findViewById<ViewGroup>(android.R.id.content)
            (container.getChildAt(0) as ComposeView).setContent {
                ParticipantRow(name = " \t", fingerprint = "ABCD", isSelf = false, isPrivateChatOnly = true)
            }
        }

        composeRule.onNodeWithText("Name unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Identity verified only for this private chat").assertIsDisplayed()
    }
}
