package org.openwebdav.messenger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.ui.feed.ChatFeedScreen
import org.openwebdav.messenger.ui.invite.InviteScreen
import org.openwebdav.messenger.ui.onboarding.CreateCommunityScreen
import org.openwebdav.messenger.ui.onboarding.JoinScreen
import org.openwebdav.messenger.ui.start.StartScreen

/**
 * The single Compose navigation surface for the thin `ui-chat-surface` vertical (arch note Choice 4: a
 * minimal state-based nav, no navigation-compose dependency added beyond the plan). It picks the start
 * destination from device state — if a chat is already joined (a persisted config produced a runtime
 * graph) the feed opens straight away, no re-onboarding (plan interaction "relaunch with saved config").
 */
@Composable
internal fun AppRoot() {
    val startAlreadyJoined = remember { AppContainer.runtimeGraph() != null }
    var screen: Screen by remember {
        mutableStateOf(if (startAlreadyJoined) Screen.Feed else Screen.Start)
    }

    when (screen) {
        Screen.Start ->
            StartScreen(
                onCreate = { screen = Screen.CreateCommunity },
                onJoin = { screen = Screen.Join },
            )

        Screen.CreateCommunity ->
            CreateCommunityScreen(
                onCreated = { screen = Screen.Feed },
                onBack = { screen = Screen.Start },
            )

        Screen.Join ->
            JoinScreen(
                onJoined = { screen = Screen.Feed },
                onBack = { screen = Screen.Start },
            )

        Screen.Feed ->
            ChatFeedScreen(
                onShowInvite = { screen = Screen.Invite },
            )

        Screen.Invite ->
            InviteScreen(
                onBack = { screen = Screen.Feed },
            )
    }
}

/** The thin nav graph for this slice: the first-launch fork, the two onboarding screens, the feed + invite. */
private sealed interface Screen {
    data object Start : Screen

    data object CreateCommunity : Screen

    data object Join : Screen

    data object Feed : Screen

    data object Invite : Screen
}
