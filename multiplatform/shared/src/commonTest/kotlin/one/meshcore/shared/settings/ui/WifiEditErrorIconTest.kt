package one.meshcore.shared.settings.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class WifiEditErrorIconTest {
    @Test
    fun usesWarningTriangle() {
        assertEquals(AppIcons.Warning, WIFI_EDIT_ERROR_ICON)
    }
}
