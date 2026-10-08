package org.openwebdav.messenger.ui.participants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openwebdav.messenger.R
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.app.RecipientReadiness
import org.openwebdav.messenger.app.RuntimeGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParticipantsScreen(
    graph: RuntimeGraph,
    onBack: () -> Unit,
) {
    val readiness by graph.recipientReadiness.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.participants_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.participants_back))
                    }
                },
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            when (val state = readiness) {
                RecipientReadiness.Loading -> {
                    ParticipantLoading()
                }
                is RecipientReadiness.Unavailable -> {
                    ParticipantUnavailable { AppContainer.retryRecipientRoster(graph) }
                }
                is RecipientReadiness.Ready -> {
                    val rows = orderedParticipants(state.participants)
                    if (rows.isEmpty()) {
                        Text(stringResource(R.string.participants_empty), Modifier.padding(vertical = 24.dp))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(rows, key = { it.fingerprint }) { row ->
                                ParticipantRow(row.displayName, row.fingerprint, row.isSelf)
                            }
                        }
                    }
                }
            }
        }
    }
}
