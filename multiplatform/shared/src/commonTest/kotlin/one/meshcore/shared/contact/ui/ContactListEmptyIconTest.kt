package one.meshcore.shared.contact.ui

import one.meshcore.shared.contact.NodeSegment
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactListEmptyIconTest {
    @Test
    fun emptyIconsMatchIosContactsEmptyView() {
        assertEquals(AppIcons.Star, contactListEmptyIcon(NodeSegment.FAVORITES))
        assertEquals(AppIcons.Group, contactListEmptyIcon(NodeSegment.CONTACTS))
        assertEquals(AppIcons.CellTower, contactListEmptyIcon(NodeSegment.REPEATERS))
        assertEquals(AppIcons.MeetingRoom, contactListEmptyIcon(NodeSegment.ROOMS))
    }
}
