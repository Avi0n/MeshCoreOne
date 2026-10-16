package one.meshcore.shared.contact.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_close
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactDetailSpokenLabelTest {
    @Test
    fun closeControlSpeaksCloseNotCancel() {
        assertEquals(Res.string.localizable_common_close, discoveringPathAbortSpokenResource())
    }
}
