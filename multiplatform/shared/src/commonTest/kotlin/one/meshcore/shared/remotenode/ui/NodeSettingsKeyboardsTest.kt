package one.meshcore.shared.remotenode.ui

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeSettingsKeyboardsTest {
    @Test
    fun numberPadExposesDoneLikeIosToolbar() {
        assertEquals(KeyboardType.Number, NODE_SETTINGS_NUMBER_KEYBOARD.keyboardType)
        assertEquals(ImeAction.Done, NODE_SETTINGS_NUMBER_KEYBOARD.imeAction)
    }

    @Test
    fun passwordFieldExposesDoneLikeIosToolbar() {
        assertEquals(KeyboardType.Password, NODE_SETTINGS_PASSWORD_KEYBOARD.keyboardType)
        assertEquals(ImeAction.Done, NODE_SETTINGS_PASSWORD_KEYBOARD.imeAction)
    }
}
