package one.meshcore.shared.contact.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class AddContactPublicKeyIconTest {
    @Test
    fun validUsesFilledCheckCircle() {
        assertEquals(AppIcons.CheckCircle, ADD_CONTACT_VALID_ICON)
    }

    @Test
    fun incompleteUsesWarningTriangle() {
        assertEquals(AppIcons.Warning, ADD_CONTACT_INVALID_COUNT_ICON)
    }
}
