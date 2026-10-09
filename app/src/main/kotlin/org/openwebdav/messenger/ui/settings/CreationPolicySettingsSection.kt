package org.openwebdav.messenger.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.R
import org.openwebdav.messenger.app.CreationPolicySettingsState
import org.openwebdav.messenger.app.CreationPolicyUpdateResult

@Composable
internal fun CreationPolicySettingsSection(
    communityId: String,
    canAdminister: Boolean,
    snackbarHostState: SnackbarHostState,
    readPolicy: suspend () -> CreationPolicySettingsState,
    updatePolicy: suspend (Boolean, Boolean) -> CreationPolicyUpdateResult,
) {
    val scope = rememberCoroutineScope()
    var state by remember(communityId) { mutableStateOf<CreationPolicySettingsState>(CreationPolicySettingsState.Loading) }
    var updating by remember(communityId) { mutableStateOf(false) }
    val failed = stringResource(R.string.creation_policy_update_failed)
    val saved = stringResource(R.string.creation_policy_saved)

    suspend fun refresh() {
        state = CreationPolicySettingsState.Loading
        state = withContext(Dispatchers.IO) { readPolicy() }
    }

    suspend fun writePolicy(
        allowPublic: Boolean,
        allowPrivate: Boolean,
    ) {
        if (updating) return
        updating = true
        val result = withContext(Dispatchers.IO) { updatePolicy(allowPublic, allowPrivate) }
        updating = false
        if (result == CreationPolicyUpdateResult.Saved) {
            refresh()
            snackbarHostState.showSnackbar(saved)
        } else {
            state =
                when (result) {
                    CreationPolicyUpdateResult.Unavailable, CreationPolicyUpdateResult.TooManyOrigins ->
                        CreationPolicySettingsState.Unavailable
                    CreationPolicyUpdateResult.Conflict, CreationPolicyUpdateResult.NotAuthorized ->
                        CreationPolicySettingsState.Blocked
                    CreationPolicyUpdateResult.Saved -> state
                }
            snackbarHostState.showSnackbar(failed)
        }
    }

    LaunchedEffect(communityId) { refresh() }
    Text(stringResource(R.string.creation_policy_title))
    Text(stringResource(R.string.creation_policy_host_exemption))
    CreationPolicySettingsContent(
        state,
        canAdminister,
        updating,
        onRetry = { scope.launch { refresh() } },
        onPublicChanged = { value ->
            val allowPrivate = (state as? CreationPolicySettingsState.Policy)?.allowPrivate ?: true
            scope.launch { writePolicy(value, allowPrivate) }
        },
        onPrivateChanged = { value ->
            val allowPublic = (state as? CreationPolicySettingsState.Policy)?.allowPublic ?: true
            scope.launch { writePolicy(allowPublic, value) }
        },
    )
    if (updating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
}
