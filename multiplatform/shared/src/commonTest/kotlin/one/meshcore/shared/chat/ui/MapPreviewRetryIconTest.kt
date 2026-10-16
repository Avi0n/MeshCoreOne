package one.meshcore.shared.chat.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class MapPreviewRetryIconTest {
    @Test
    fun retryUsesTheRefreshSymbol() {
        assertEquals(AppIcons.Refresh, MAP_PREVIEW_RETRY_ICON)
        assertEquals(40, MAP_PREVIEW_RETRY_BUTTON_DP)
    }
}
