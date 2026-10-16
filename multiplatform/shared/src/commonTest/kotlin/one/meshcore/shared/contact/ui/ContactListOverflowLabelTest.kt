package one.meshcore.shared.contact.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_list_options
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactListOverflowLabelTest {
    @Test
    fun overflowButtonSpeaksOptions() {
        assertEquals(Res.string.contacts_list_options, nodesOverflowMenuLabel())
    }
}
