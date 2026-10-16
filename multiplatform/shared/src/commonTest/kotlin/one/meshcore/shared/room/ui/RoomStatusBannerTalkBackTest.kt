package one.meshcore.shared.room.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS `RoomStatusBanner`: TalkBack label is the title; the caption is the hint. The whole banner
 * is one control, not a title plus a separate button.
 */
class RoomStatusBannerTalkBackTest {
    @Test
    fun bannerIsAListItemNotAFormCaptionStack() {
        assertEquals(AppIcons.Warning, ROOM_STATUS_DISCONNECTED_ICON)
        assertEquals(AppIcons.Visibility, ROOM_STATUS_VIEW_ONLY_ICON)
    }
}
