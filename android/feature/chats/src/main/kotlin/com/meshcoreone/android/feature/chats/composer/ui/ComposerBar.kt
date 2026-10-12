// PortedFrom: MC1/Views/Chats/Components/ChatInputBar.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/ChatShareMenu.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/ChatComposerTextView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ChatConversationInputBar.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.composer.ComposerSendPhase
import com.meshcoreone.android.feature.chats.composer.ComposerUiState

/** What the "+" menu offers; computed by the holder so the bar stays stateless. */
data class ShareAvailability(val location: Boolean, val myInfo: Boolean)

data class ComposerBarActions(
    val onFieldValueChange: (TextFieldValue) -> Unit,
    val onSend: () -> Unit,
    val onShareLocation: () -> Unit,
    val onShareContact: () -> Unit,
    val onShareMyInfo: () -> Unit,
    /** Null hides the emoji entry point (no catalog bound). */
    val onOpenEmoji: (() -> Unit)?,
    val onDismissFailure: () -> Unit,
)

/**
 * Multi-line composer bar: share menu, field with encryption badge, emoji entry, byte counter and
 * send button (disabled / sending / failed). Insets are the caller's job (see ChatConversationScreen:
 * `navigationBarsPadding().imePadding()`); every control has a 48dp target and a TalkBack description.
 */
@Composable
fun ComposerBar(
    state: ComposerUiState,
    fieldValue: TextFieldValue,
    placeholder: String,
    share: ShareAvailability,
    actions: ComposerBarActions,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            (state.sendPhase as? ComposerSendPhase.Failed)?.let { failure ->
                Text(
                    sendFailureText(resources, failure.reason),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                ShareMenu(share, actions)
                ComposerField(state, fieldValue, placeholder, actions, focusRequester, Modifier.weight(1f))
                SendColumn(state, actions)
            }
        }
    }
}

@Composable
private fun ShareMenu(share: ShareAvailability, actions: ComposerBarActions) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, modifier = Modifier.sharedTouchTarget()) {
        Icon(ComposerIcons.Add, stringResource(AppChatsStrings.chatsInputShareButtonAccessibilityLabel))
    }
    DropdownMenu(expanded, { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(AppChatsStrings.chatsShareLocation)) },
            enabled = share.location,
            onClick = { expanded = false; actions.onShareLocation() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(AppChatsStrings.chatsShareContact)) },
            onClick = { expanded = false; actions.onShareContact() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(AppChatsStrings.chatsShareMyInfo)) },
            enabled = share.myInfo,
            onClick = { expanded = false; actions.onShareMyInfo() },
        )
    }
}

@Composable
private fun ComposerField(
    state: ComposerUiState,
    fieldValue: TextFieldValue,
    placeholder: String,
    actions: ComposerBarActions,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    val label = stringResource(AppChatsStrings.chatsInputAccessibilityLabel)
    val hint = stringResource(AppChatsStrings.chatsInputAccessibilityHint)
    val security = stringResource(if (state.isEncrypted) AppChatsStrings.chatsInputEncrypted else AppChatsStrings.chatsInputNotEncrypted)
    val tooLong = stringResource(AppChatsStrings.chatsInputTooLong)
    OutlinedTextField(
        value = fieldValue,
        onValueChange = actions.onFieldValueChange,
        modifier = modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                // Hardware Return sends only when the button is enabled; otherwise normal multiline editing handles it.
                val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (state.canSend && isEnter && !event.isShiftPressed) {
                    if (event.type == KeyEventType.KeyDown) actions.onSend()
                    true
                } else {
                    false
                }
            }
            .semantics {
                contentDescription = "$label, $security"
                stateDescription = hint
                if (state.isOverLimit) error(tooLong)
            },
        placeholder = { Text(placeholder) },
        trailingIcon = {
            Icon(
                (if (state.isEncrypted) MeshSymbol.LOCK else MeshSymbol.WARNING).vector,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        isError = state.isOverLimit,
        maxLines = MAX_VISIBLE_LINES,
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

@Composable
private fun SendColumn(state: ComposerUiState, actions: ComposerBarActions) {
    val resources = LocalResources.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        actions.onOpenEmoji?.let { openEmoji ->
            IconButton(onClick = openEmoji, modifier = Modifier.sharedTouchTarget()) {
                Icon(ComposerIcons.Emoji, stringResource(AppChatsStrings.reactionsMoreEmojis))
            }
        }
        if (state.showCounter) {
            Text(
                "${state.byteCount}/${state.maxBytes}",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.isOverLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = AppChatsStrings.chatsInputCharacterCount(resources, state.byteCount, state.maxBytes)
                },
            )
        }
        val label = stringResource(if (state.isOverLimit) AppChatsStrings.chatsInputTooLong else AppChatsStrings.chatsInputSendMessage)
        val hintText = sendHintText(resources, state.sendHint, state.bytesOverLimit)
        FilledIconButton(
            onClick = actions.onSend,
            enabled = state.canSend,
            modifier = Modifier.sharedTouchTarget().semantics {
                contentDescription = label
                stateDescription = hintText
            },
        ) {
            if (state.isSending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(ComposerIcons.ArrowUp, contentDescription = null)
        }
    }
}

private const val MAX_VISIBLE_LINES = 6
