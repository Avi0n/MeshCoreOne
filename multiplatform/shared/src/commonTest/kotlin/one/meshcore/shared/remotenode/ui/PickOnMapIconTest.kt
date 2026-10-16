package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class PickOnMapIconTest {
    @Test
    fun usesOutlinedMap() {
        assertEquals(AppIcons.Map, PICK_ON_MAP_ICON)
    }
}
