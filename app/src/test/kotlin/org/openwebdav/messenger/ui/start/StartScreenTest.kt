package org.openwebdav.messenger.ui.start

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.ui.MainActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun restore_is_available_alongside_create_and_join() {
        var restoreCount = 0
        composeRule.activity.setContent {
            StartScreen(onCreate = {}, onJoin = {}, onRestore = { restoreCount++ })
        }
        composeRule.onNodeWithText("Restore account").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Create a community").assertIsDisplayed()
        composeRule.onNodeWithText("Join by invite").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, restoreCount) }
    }
}
