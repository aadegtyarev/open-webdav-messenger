package org.openwebdav.messenger.keystore

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ActiveCommunityStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearStore() {
        context.getSharedPreferences("owdm.active-community", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun selectedCommunitySurvivesStoreRecreation() {
        ActiveCommunityStore(context).select("community-b")

        assertEquals("community-b", ActiveCommunityStore(context).load("community-a"))
    }

    @Test
    fun missingSelectionUsesFirstJoinedCommunityFallback() {
        assertEquals("community-a", ActiveCommunityStore(context).load("community-a"))
    }
}
