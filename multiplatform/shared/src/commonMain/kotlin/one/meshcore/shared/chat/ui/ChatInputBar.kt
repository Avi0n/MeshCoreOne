package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_input_accessibility_hint
import one.meshcore.shared.resources.chats_input_accessibility_label
import one.meshcore.shared.resources.chats_input_character_count
import one.meshcore.shared.resources.chats_input_encrypted
import one.meshcore.shared.resources.chats_input_not_encrypted
import one.meshcore.shared.resources.chats_input_remove_characters
import one.meshcore.shared.resources.chats_input_requires_connection
import one.meshcore.shared.resources.chats_input_send_message
import one.meshcore.shared.resources.chats_input_tap_to_send
import one.meshcore.shared.resources.chats_input_too_long
import one.meshcore.shared.resources.chats_input_type_first
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The chat composer input bar: an optional leading accessory, a multiline growing text field with an
 * encryption lock glyph, and a send button with an over-limit byte counter. Renders [ComposerState] and
 * reports edits/sends through the callbacks; the hosting screen owns the [one.meshcore.shared.chat.ComposerViewModel].
 *
 * This is safety chrome under the Liquid-Glass rule, so it renders on a solid [Surface] with no
 * translucency — the composer must stay legible under outdoor glare and high-contrast modes. Send
 * gating (`.ready`, cooldown, non-empty, within budget) is [ComposerState.canSend]; [isReady] is the
 * live connection gate the caller supplies.
 */
@Composable
fun ChatInputBar(
    state: ComposerState,
    isReady: Boolean,
    isEncrypted: Boolean,
    placeholder: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
) {
    val canSend = state.canSend(isReady)
    val composerFocus = remember { FocusRequester() }
    var composerWasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(state.focusRequest) {
        if (state.focusRequest > 0) composerFocus.requestFocus()
    }
    val haptics = rememberAppHaptics()
    val density = LocalDensity.current
    val navigationInset = navigationInsetOutsideIme(
        imeBottom = currentImeBottomPx(),
        navigationBottom = WindowInsets.navigationBars.getBottom(density),
    )
    val sendIfAllowed: () -> Unit = {
        if (canSend) {
            haptics.start()
            onSend()
        }
    }
    // The bar's container draws under the navigation bar and keyboard. The IME inset already contains the
    // navigation area, so keep the separate navigation lift only while the keyboard is closed.
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = with(density) { navigationInset.toDp() })
                .chatImePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = COMPOSER_BAR_HORIZONTAL_PADDING_DP.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(COMPOSER_CONTROL_GAP_DP.dp),
            ) {
                leading?.invoke()
                // Material's label keeps encryption separate from editable text. A field content
                // description would replace the draft during accessibility character navigation.
                val composerLabel = composerAccessibilityLabel(
                    fieldLabel = stringResource(Res.string.chats_input_accessibility_label),
                    encryptionStatus = if (isEncrypted) {
                        stringResource(Res.string.chats_input_encrypted)
                    } else {
                        stringResource(Res.string.chats_input_not_encrypted)
                    },
                )
                val composerHint = stringResource(Res.string.chats_input_accessibility_hint)
                AppOutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(composerFocus)
                        .onFocusChanged { focus ->
                            if (focus.isFocused && !composerWasFocused) onFocus()
                            composerWasFocused = focus.isFocused
                        }
                        .semantics { stateDescription = composerHint }
                        .onPreviewKeyEvent { event ->
                            val isEnterKey = event.key == Key.Enter || event.key == Key.NumPadEnter
                            val shouldSend =
                                event.type == KeyEventType.KeyDown && isEnterKey && !event.isShiftPressed
                            if (shouldSend) {
                                sendIfAllowed()
                                true
                            } else {
                                false
                            }
                        },
                    label = { Text(composerLabel) },
                    placeholder = { Text(placeholder) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendIfAllowed() }),
                    maxLines = COMPOSER_MAX_LINES,
                    shape = MaterialTheme.shapes.extraLarge,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    trailingIcon = { EncryptionGlyph(isEncrypted = isEncrypted) },
                )
                SendColumn(
                    state = state,
                    isReady = isReady,
                    canSend = canSend,
                    onSend = sendIfAllowed,
                )
            }
        }
    }
}

@Composable
private fun SendColumn(
    state: ComposerState,
    isReady: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(COMPOSER_SEND_COLUMN_GAP_DP.dp),
    ) {
        val label = if (state.isOverLimit) {
            stringResource(Res.string.chats_input_too_long)
        } else {
            stringResource(Res.string.chats_input_send_message)
        }
        val hint = sendHint(state = state, isReady = isReady, canSend = canSend)
        FilledIconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = hint
            },
        ) {
            Icon(
                imageVector = AppIcons.Send,
                contentDescription = null,
            )
        }
        if (state.shouldShowCharacterCount) {
            CharacterCount(byteCount = state.byteCount, maxBytes = state.maxBytes, isOverLimit = state.isOverLimit)
        }
    }
}

@Composable
private fun sendHint(state: ComposerState, isReady: Boolean, canSend: Boolean): String = when {
    state.isOverLimit ->
        formattedStringResource(Res.string.chats_input_remove_characters, state.byteCount - state.maxBytes)
    !isReady -> stringResource(Res.string.chats_input_requires_connection)
    canSend -> stringResource(Res.string.chats_input_tap_to_send)
    else -> stringResource(Res.string.chats_input_type_first)
}

@Composable
private fun CharacterCount(byteCount: Int, maxBytes: Int, isOverLimit: Boolean) {
    val spoken = formattedStringResource(Res.string.chats_input_character_count, byteCount, maxBytes)
    Text(
        text = "$byteCount/$maxBytes",
        style = MaterialTheme.typography.labelSmall,
        color = if (isOverLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

@Composable
private fun EncryptionGlyph(isEncrypted: Boolean) {
    Icon(
        imageVector = if (isEncrypted) AppIcons.Lock else AppIcons.LockOpen,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(COMPOSER_LOCK_ICON_DP.dp),
    )
}

/** Field label plus encryption status. The draft is not part of this string. */
internal fun composerAccessibilityLabel(fieldLabel: String, encryptionStatus: String): String =
    "$fieldLabel, $encryptionStatus"

private const val COMPOSER_MAX_LINES = 6

/** Horizontal inset for the composer row, matching the M3 compact content padding. */
internal const val COMPOSER_BAR_HORIZONTAL_PADDING_DP = 16

/** Gap between the share control, field, and send column. */
internal const val COMPOSER_CONTROL_GAP_DP = 8

/** Gap between the send button and the over-limit byte counter. */
internal const val COMPOSER_SEND_COLUMN_GAP_DP = 4

/** Encryption lock sits in the field trailing slot at caption size, not as a sibling icon button. */
internal const val COMPOSER_LOCK_ICON_DP = 20
