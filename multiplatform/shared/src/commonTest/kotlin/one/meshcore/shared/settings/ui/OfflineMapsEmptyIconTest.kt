package one.meshcore.shared.settings.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineMapsEmptyIconTest {
    @Test
    fun emptyUsesMapGlyph() {
        assertEquals(AppIcons.Map, OFFLINE_MAPS_EMPTY_ICON)
    }
}
