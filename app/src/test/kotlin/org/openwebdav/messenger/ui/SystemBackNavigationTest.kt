package org.openwebdav.messenger.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemBackNavigationTest {
    @Test
    fun feedBackReturnsToChatsAndInviteBackReturnsToFeed() {
        assertEquals(Screen.CommunityList, Screen.Feed.systemBackDestination(hasCommunities = true))
        assertEquals(Screen.Feed, Screen.Invite.systemBackDestination(hasCommunities = true))
    }

    @Test
    fun onboardingBackReturnsToStartOnFirstLaunch() {
        assertEquals(Screen.Start, Screen.CreateCommunity.systemBackDestination(hasCommunities = false))
        assertEquals(Screen.Start, Screen.Join.systemBackDestination(hasCommunities = false))
    }

    @Test
    fun savedDestinationRestoresAcrossRecreation() {
        assertEquals(Screen.Settings, screenForSavedRoute(Screen.Settings.persistedRoute()))
    }
}
