package org.openwebdav.messenger.ui.feed

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization

/** String-backed editor keeps Compose's selection/composition state while callback identity is session-bound. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerDraftField(
    draft: String,
    sessionToken: Long,
    onDraft: (String, Long) -> Unit,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier,
) {
    // Keep editor identity stable for IME selection/composition; old callback objects retain this token.
    val capturedSessionToken = sessionToken
    val onValueChange =
        remember(capturedSessionToken, onDraft) {
            composerDraftChangeHandler(onDraft, capturedSessionToken)
        }
    OutlinedTextField(
        value = draft,
        onValueChange = onValueChange,
        enabled = enabled,
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier,
    )
}

/** Build the exact callback installed on the editor; retained callbacks keep their original session. */
internal fun composerDraftChangeHandler(
    onDraft: (String, Long) -> Unit,
    sessionToken: Long,
): (String) -> Unit = { value -> onDraft(value, sessionToken) }
