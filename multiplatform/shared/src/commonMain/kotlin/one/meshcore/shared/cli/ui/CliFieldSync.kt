package one.meshcore.shared.cli.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** A view-model rewrite moves the caret to the end. Matching text leaves the field alone. */
internal fun rewrittenCliField(currentInput: String, fieldText: String): TextFieldValue? =
    if (currentInput == fieldText) {
        null
    } else {
        TextFieldValue(currentInput, TextRange(currentInput.length))
    }

/** Ghost text follows only a collapsed caret sitting on [length]. */
internal fun cliCursorAtEnd(collapsed: Boolean, cursor: Int, length: Int): Boolean = collapsed && cursor == length
