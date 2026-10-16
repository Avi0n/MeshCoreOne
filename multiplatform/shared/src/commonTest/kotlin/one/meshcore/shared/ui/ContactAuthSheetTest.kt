package one.meshcore.shared.ui

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.remotenode.NodeAuthPurpose
import one.meshcore.shared.remotenode.testContact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ContactAuthSheetTest {
    @Test
    fun repeaterOpensManageAsRepeater() {
        val contact = testContact(type = ContactType.REPEATER)
        val sheet = contactAuthSheet(contact, NodeAuthPurpose.MANAGE)
        assertEquals(RemoteNodeRole.REPEATER, sheet?.role)
        assertEquals(NodeAuthPurpose.MANAGE, sheet?.purpose)
        assertSame(contact, sheet?.contact)
    }

    @Test
    fun roomOpensTelemetryAsRoomServer() {
        val contact = testContact(type = ContactType.ROOM)
        val sheet = contactAuthSheet(contact, NodeAuthPurpose.TELEMETRY)
        assertEquals(RemoteNodeRole.ROOM_SERVER, sheet?.role)
        assertEquals(NodeAuthPurpose.TELEMETRY, sheet?.purpose)
    }

    @Test
    fun chatContactDoesNotOpenAuth() {
        assertNull(contactAuthSheet(testContact(type = ContactType.CHAT), NodeAuthPurpose.MANAGE))
    }
}
