package one.meshcore.shared.cli.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CliCaptureField(
    input: TextFieldValue,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onInputChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onPreviewKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val inputStyle = LocalTextStyle.current.copy(
        fontFamily = FontFamily.Monospace,
        color = Color.Transparent,
        fontSize = CAPTURE_FONT_SIZE,
    )
    BasicTextField(
        value = input,
        onValueChange = onInputChange,
        singleLine = true,
        enabled = enabled,
        textStyle = inputStyle,
        cursorBrush = SolidColor(Color.Transparent),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        keyboardOptions = CLI_CAPTURE_KEYBOARD,
        modifier = modifier
            .size(CAPTURE_FIELD_SIZE)
            .alpha(CAPTURE_FIELD_ALPHA)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .onPreviewKeyEvent(onPreviewKey)
            .clearAndSetSemantics { },
    )
}

internal fun handleCliCaptureKey(event: KeyEvent, ghostText: String, actions: CliTerminalActions): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Enter, Key.NumPadEnter -> {
            actions.onSubmit()
            true
        }
        Key.Tab -> {
            actions.onTab()
            true
        }
        Key.DirectionUp -> {
            actions.onHistoryUp()
            true
        }
        Key.DirectionDown -> {
            actions.onHistoryDown()
            true
        }
        Key.DirectionRight -> {
            if (ghostText.isNotEmpty()) {
                actions.onAcceptGhost()
                true
            } else {
                false
            }
        }
        else -> false
    }
}

private val CAPTURE_FIELD_SIZE = 1.dp
private const val CAPTURE_FIELD_ALPHA = 0.01f
private val CAPTURE_FONT_SIZE = 1.sp

private val CLI_CAPTURE_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
    imeAction = ImeAction.Send,
)
