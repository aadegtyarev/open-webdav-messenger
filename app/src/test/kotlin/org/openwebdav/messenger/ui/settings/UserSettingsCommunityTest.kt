package org.openwebdav.messenger.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserSettingsCommunityTest {
    @Before
    fun resetSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("owdm_user", Context.MODE_PRIVATE).edit().clear().commit()
        UserSettings.init(context)
    }

    @Test
    fun host_and_metadata_are_isolated_by_community() {
        UserSettings.setHostFor("a", true)
        UserSettings.setCommunityMetadata("a", pollFloor = 300, retentionDays = 60)
        UserSettings.setHostFor("b", false)
        UserSettings.setCommunityMetadata("b", pollFloor = 120, retentionDays = 30)

        assertTrue(UserSettings.isHostFor("a"))
        assertFalse(UserSettings.isHostFor("b"))
        assertEquals(300, UserSettings.pollFloorFor("a"))
        assertEquals(60, UserSettings.retentionDaysFor("a"))
        assertEquals(120, UserSettings.pollFloorFor("b"))
        assertEquals(30, UserSettings.retentionDaysFor("b"))
    }
}
