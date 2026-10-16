package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsFailedToLoadIconTest {
    @Test
    fun usesWarningTriangle() {
        assertEquals(AppIcons.Warning, SETTINGS_FAILED_TO_LOAD_ICON)
    }
}
