package one.meshcore.shared.ui

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS `WiFiAddressFields` uses a URL keyboard, never capitalizes, and turns autocorrect off so a
 * hostname is not rewritten while typing.
 */
class WifiAddressFieldOptionsTest {
    @Test
    fun hostKeyboardMatchesIosUrlField() {
        assertEquals(KeyboardType.Uri, WIFI_HOST_KEYBOARD.keyboardType)
        assertEquals(KeyboardCapitalization.None, WIFI_HOST_KEYBOARD.capitalization)
        assertEquals(false, WIFI_HOST_KEYBOARD.autoCorrectEnabled)
        assertEquals(ImeAction.Next, WIFI_HOST_KEYBOARD.imeAction)
    }

    @Test
    fun portKeyboardIsNumeric() {
        assertEquals(KeyboardType.Number, WIFI_PORT_KEYBOARD.keyboardType)
        assertEquals(ImeAction.Done, WIFI_PORT_KEYBOARD.imeAction)
    }
}
