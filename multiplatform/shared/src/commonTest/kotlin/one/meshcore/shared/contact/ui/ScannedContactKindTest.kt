package one.meshcore.shared.contact.ui

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_node_kind_contact
import one.meshcore.shared.resources.contacts_node_kind_repeater
import one.meshcore.shared.resources.contacts_node_kind_room
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ScannedContactKindTest {
    @Test
    fun eachContactTypeKeepsItsIconAndLabel() {
        val chat = scannedContactKind(ContactType.CHAT)
        assertEquals(AppIcons.Person, chat.icon)
        assertEquals(Res.string.contacts_node_kind_contact, chat.label)

        val repeater = scannedContactKind(ContactType.REPEATER)
        assertEquals(AppIcons.CellTower, repeater.icon)
        assertEquals(Res.string.contacts_node_kind_repeater, repeater.label)

        val room = scannedContactKind(ContactType.ROOM)
        assertEquals(AppIcons.Forum, room.icon)
        assertEquals(Res.string.contacts_node_kind_room, room.label)
    }
}
