package one.meshcore.shared.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomAuthNotFoundIconTest {
    @Test
    fun missingUsesWarningTriangle() {
        assertEquals(AppIcons.Warning, ROOM_AUTH_NOT_FOUND_ICON)
    }
}
