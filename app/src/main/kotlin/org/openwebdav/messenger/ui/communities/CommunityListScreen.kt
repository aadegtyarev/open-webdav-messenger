package org.openwebdav.messenger.ui.communities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openwebdav.messenger.app.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityListScreen(
    onSelectCommunity: (communityId: String) -> Unit,
    onCreate: () -> Unit,
) {
    val communities = remember { AppContainer.communities() }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Communities") })
        },
    ) { padding ->
        if (communities.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No communities yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            ) {
                for (community in communities) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppContainer.switchToCommunity(community.id)
                                onSelectCommunity(community.id)
                            }
                            .padding(16.dp)
                            .semantics { contentDescription = community.name },
                    ) {
                        Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(community.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
