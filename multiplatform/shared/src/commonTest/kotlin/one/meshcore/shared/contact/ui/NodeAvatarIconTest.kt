package one.meshcore.shared.contact.ui

import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

/** Material analog of iOS `NodeAvatar` SF Symbols (antenna / door / number). */
class NodeAvatarIconTest {
    @Test
    fun repeaterUsesCellTower() {
        assertEquals(AppIcons.CellTower, nodeAvatarIcon(AvatarCategory.REPEATER))
    }

    @Test
    fun roomUsesMeetingRoom() {
        assertEquals(AppIcons.MeetingRoom, nodeAvatarIcon(AvatarCategory.ROOM))
    }

    @Test
    fun channelUsesTag() {
        assertEquals(AppIcons.Tag, nodeAvatarIcon(AvatarCategory.CHANNEL))
    }
}
