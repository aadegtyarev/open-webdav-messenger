package org.openwebdav.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.ui.communities.CommunityListScreen
import org.openwebdav.messenger.ui.feed.ChatFeedScreen
import org.openwebdav.messenger.ui.invite.InviteScreen
import org.openwebdav.messenger.ui.onboarding.CreateCommunityScreen
import org.openwebdav.messenger.ui.onboarding.JoinScreen
import org.openwebdav.messenger.ui.settings.SettingsScreen
import org.openwebdav.messenger.ui.settings.UserSettings
import org.openwebdav.messenger.ui.start.StartScreen

/**
 * The single Compose navigation surface for the thin `ui-chat-surface` vertical (arch note Choice 4: a
 * minimal state-based nav, no navigation-compose dependency added beyond the plan).
 *
 * Process-start wiring runs asynchronously (the `Application` warm-starts off the main thread; Keystore/IO
 * must not block the UI). So this collects [AppContainer.ready] and shows a brief loading state until the
 * wiring has resolved the start graph — only THEN does it pick the start destination from device state:
 * if a chat is already joined (a persisted config produced a runtime graph) the feed opens straight away,
 * no re-onboarding (plan interaction "relaunch with saved config"; fixes the start-destination race where
 * a synchronous read raced the async warm-start and re-onboarded a returning user).
 */
@Composable
internal fun AppRoot() {
    val ready by AppContainer.ready.collectAsStateWithLifecycle()
    if (!ready) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Open WebDAV Messenger",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "v0.14.0+",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator()
        }
        return
    }
    AppNav()
}

/** The post-readiness navigation graph — the start destination is resolved from the warmed-up graph. */
@Composable
private fun AppNav() {
    val startAlreadyJoined = remember { AppContainer.runtimeGraph() != null }
    val hasCommunities = remember { AppContainer.communities().isNotEmpty() }
    var screen: Screen by remember {
        mutableStateOf(
            when {
                startAlreadyJoined -> Screen.Feed
                hasCommunities -> Screen.CommunityList
                else -> Screen.Start
            },
        )
    }

    when (screen) {
        Screen.Start ->
            StartScreen(
                onCreate = { screen = Screen.CreateCommunity },
                onJoin = { screen = Screen.Join },
            )

        Screen.CommunityList ->
            CommunityListScreen(
                onOpenFeed = { screen = Screen.Feed },
                onCreate = { screen = Screen.CreateCommunity },
                onJoin = { screen = Screen.Join },
                onSettings = { screen = Screen.Settings },
            )

        Screen.Settings -> {
            val host = remember { UserSettings.isHost }
            val retentionDays = remember { UserSettings.communityRetentionWindowDays }
            val pollFloor = remember { UserSettings.communityMinPollSeconds }
            SettingsScreen(
                onBack = { screen = Screen.CommunityList },
                isHost = host,
                retentionWindowDays = retentionDays,
                communityPollFloor = pollFloor,
                onRetentionChanged = { days ->
                    AppContainer.updateCommunityMetadata(days, UserSettings.communityMinPollSeconds)
                },
                onPollFloorChanged = { seconds ->
                    AppContainer.updateCommunityMetadata(UserSettings.communityRetentionWindowDays, seconds)
                },
            )
        }

        Screen.CreateCommunity ->
            CreateCommunityScreen(
                onCreated = { screen = Screen.CommunityList },
                onBack = { screen = Screen.CommunityList },
            )

        Screen.Join ->
            JoinScreen(
                onJoined = { screen = Screen.CommunityList },
                onBack = { screen = Screen.CommunityList },
            )

        Screen.Feed ->
            ChatFeedScreen(
                onShowInvite = { screen = Screen.Invite },
                onBack = { screen = Screen.CommunityList },
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

    data object CommunityList : Screen

    data object CreateCommunity : Screen

    data object Join : Screen

    data object Feed : Screen

    data object Invite : Screen

    data object Settings : Screen
}
